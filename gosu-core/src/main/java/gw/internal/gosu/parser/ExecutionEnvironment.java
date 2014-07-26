/*
 * Copyright 2014 Guidewire Software, Inc.
 */

package gw.internal.gosu.parser;

import gw.config.CommonServices;
import gw.fs.IDirectory;
import gw.internal.gosu.module.DefaultSingleModule;
import gw.internal.gosu.module.GlobalModule;
import gw.lang.cli.SystemExitIgnoredException;
import gw.lang.gosuc.GosucModule;
import gw.lang.gosuc.GosucUtil;
import gw.lang.init.GosuPathEntry;
import gw.lang.parser.CoercionUtil;
import gw.lang.parser.GosuParserFactory;
import gw.lang.parser.IGosuParser;
import gw.lang.parser.IGosuProgramParser;
import gw.lang.parser.ILanguageLevel;
import gw.lang.parser.IParseResult;
import gw.lang.parser.ParserOptions;
import gw.lang.reflect.IType;
import gw.lang.reflect.ITypeRef;
import gw.lang.reflect.TypeSystem;
import gw.lang.reflect.gs.BytecodeOptions;
import gw.lang.reflect.gs.GosuClassPathThing;
import gw.lang.reflect.gs.GosuClassTypeLoader;
import gw.lang.reflect.java.JavaTypes;
import gw.lang.reflect.module.IExecutionEnvironment;
import gw.lang.reflect.module.IModule;
import gw.util.GosuExceptionUtil;
import gw.util.ILogger;

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.net.URLDecoder;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class ExecutionEnvironment implements IExecutionEnvironment
{
  private static ExecutionEnvironment THE_ONE = new ExecutionEnvironment();
  public static final String CLASS_REDEFINER_THREAD = "Gosu class redefiner";

  /**
   * "Special" java classes that will be used to locate "special" JARs which should be
   * included in the classpath. This is a replacement to old SPECIAL_FILES variable.
   */
  private static final List<String> SPECIAL_CLASSES = Arrays.asList(
          "javax.servlet.Servlet",
          "javax.servlet.http.HttpServletRequest"
  );

  private IModule _defaultModule;
  private TypeSystemState _state = TypeSystemState.STOPPED;

  public static ExecutionEnvironment instance()
  {
    return THE_ONE;
  }

  public void initializeDefaultSingleModule( List<? extends GosuPathEntry> pathEntries ) {
    _state = TypeSystemState.STARTING;
    try {
      DefaultSingleModule singleModule = _defaultModule == null ? new DefaultSingleModule( this ) : (DefaultSingleModule)_defaultModule;
      List<IDirectory> allSources = new ArrayList<IDirectory>();
      List<IDirectory> allRoots = new ArrayList<IDirectory>();
      for( GosuPathEntry pathEntry : pathEntries )
      {
        allRoots.add(pathEntry.getRoot());
        allSources.addAll(pathEntry.getSources());
      }
      singleModule.configurePaths(createDefaultClassPath(), allSources);
      singleModule.setRoots(allRoots);
      _defaultModule = singleModule;
      singleModule.initializeTypeLoaders();
      CommonServices.getEntityAccess().init();

      startSneakyDebugThread();
    } finally {
      _state = TypeSystemState.STARTED;
    }
  }

  public void uninitializeDefaultSingleModule() {
    _state = TypeSystemState.STOPPING;
    try {
      if (_defaultModule != null) {
        DefaultSingleModule m = (DefaultSingleModule) _defaultModule;
        m.getModuleTypeLoader().uninitializeTypeLoaders();
        m.getModuleTypeLoader().reset();
        m.setRoots(Collections.<IDirectory>emptyList());
        m.configurePaths(Collections.<IDirectory>emptyList(), Collections.<IDirectory>emptyList());
      }
      _defaultModule = null;
    } finally {
      _state = TypeSystemState.STOPPED;
    }
  }

  public void initializeCompiler(GosucModule gosucModule) {
    _state = TypeSystemState.STARTING;
    try {
      DefaultPlatformHelper.DISABLE_COMPILE_TIME_ANNOTATION = true;

      DefaultSingleModule module = new DefaultSingleModule( this, gosucModule.getName() );
      module.setRoots(GosucUtil.toDirectories(gosucModule.getContentRoots()));
      module.configurePaths(GosucUtil.toDirectories(gosucModule.getClasspath()), GosucUtil.toDirectories(gosucModule.getAllSourceRoots()));
      _defaultModule = module;

      module.initializeTypeLoaders();
      CommonServices.getEntityAccess().init();

      FrequentUsedJavaTypeCache.instance( this ).init();
    } finally {
      _state = TypeSystemState.STARTED;
    }
  }

  public void uninitializeCompiler() {
    _state = TypeSystemState.STOPPING;
    try {
      if (_defaultModule != null) {
        GlobalModule m = (GlobalModule) _defaultModule;
        m.getModuleTypeLoader().uninitializeTypeLoaders();
        m.getModuleTypeLoader().reset();
        m.setRoots(Collections.<IDirectory>emptyList());
        m.configurePaths(Collections.<IDirectory>emptyList(), Collections.<IDirectory>emptyList());

        GosuClassPathThing.cleanup();
      }

      _defaultModule = null;
    } finally {
      _state = TypeSystemState.STOPPED;
    }
  }

  public IModule getGlobalModule() {
    return _defaultModule;
  }

  public TypeSystemState getState() {
    return _state;
  }

  public void shutdown() {
    _defaultModule.getModuleTypeLoader().shutdown();
    THE_ONE = new ExecutionEnvironment();
  }

  /**
   * Detect whether or not the jdwp agent is alive in this process, if so start
   * a thread that wakes up every N seconds and checks to see if the ReloadClassesIndicator
   * Java class has been redefined by a debugger.  If so, it reloads Gosu classes
   * that have changed.
   * <p>
   * Why, you ask?  Well since Gosu classes are not compiled to disk, the IDE hosting
   * Gosu can't simply send the bytes in a conventional JDI redefineClasses() call.
   * Yet it somehow needs to at least inform Gosu's type system in the target process
   * that Gosu classes have changed.  The JVMTI doesn't offer much help; there's no
   * way to field an arbitrary call from the JDWP client, or for the client to send an
   * arbitrary message.  Nor is it possible to leverage the JVMTI's ability to handle
   * method invocation etc. because the target thread must be suspended at a
   * breakpoint, which is not necessarily the case during compilation, and certainly
   * isn't the case for a thread dedicated to fielding such a call.  What to do?
   * <p>
   * We can leverage redefineClasses() after all.  The idea is for the IDE compiler
   * to redefine a class (via asm) designated as the "ReloadClassIndicator".  This class lives
   * inside Gosu's type system.  It has a single method: public static long timestamp()
   * and returns a literal value.  If the target process is being debugged (jdwp
   * agent detection), a thread in the target process starts immediately and waits a
   * few seconds before calling the timestamp() method, it does this in a forever loop.
   * If the timestamp value changes, we assume the IDE redefined the class with a new
   * value to indicate classes have changed.  In turn we find and reload changed
   * classes.  What could be more straight forward?
   * <p>
   * An alternative approach would be for the IDE to establish an additional line
   * of communication with the target process e.g., socket, memory, whatever.  But
   * that is messy (requires config on user's end) and error prone.  One debug
   * socket is plenty.
   * <p>
   * Improvements to this strategy include supplying not only an indication that stuff
   * has changed, but also the names of the classes that have changed.  This would
   * releive the target process from having to keep track timestamps on all loaded
   * classes. This could be implemented by having the class return an array of names.
   * An even better improvement would be to include not just the names, but also the
   * source of the classes.  This would enable the debuger to modify in memory the classes
   * during a remote debugging session.
   */
  private void startSneakyDebugThread() {
    if( !BytecodeOptions.JDWP_ENABLED.get() ) {
      return;
    }
    ContextSensitiveCodeRunner blah = new ContextSensitiveCodeRunner();
    Thread sneakyDebugThread =
        new Thread(
            new Runnable() {
              public synchronized void run() {
                long timestamp = ReloadClassesIndicator.timestamp();
                long now = 0;
                while (getState() != TypeSystemState.STOPPED) {
                  try {
                    wait(2000);
                    now = ReloadClassesIndicator.timestamp();
                    if (now > timestamp) {
                      String script = ReloadClassesIndicator.getScript();
                      if (script != null && script.length() > 0) {
                        runScript(script);
                      }
                      else {
                        refreshTypes();
                      }
                    }
                  } catch (Exception e) {
                    e.printStackTrace();
                  } finally {
                    timestamp = now;
                  }
                }
              }

              private void refreshTypes() {
                String[] types = ReloadClassesIndicator.changedTypes();
                System.out.println("Refreshing " + types.length + " types at " + new Date());
                if (types.length > 0) {
                  for (String name : types) {
                    IType type = TypeSystem.getByFullNameIfValid(name);
                    if (type != null) {
                      TypeSystem.refresh((ITypeRef) type);
                      // Also update enhancement index if type is an enhancement
                      if( type instanceof IGosuEnhancementInternal ) {
                        ((GosuClassTypeLoader)type.getTypeLoader()).getEnhancementIndex().addEntry(
                          ((IGosuEnhancementInternal)type).getEnhancedType(), (IGosuEnhancementInternal)type );
                      }
                    }
                  }
                }
                CommonServices.getEntityAccess().reloadedTypes(types);
              }

              private void runScript( String strScript ) {
                String[] result = evaluate(strScript);
                if( result[0] != null && result[0].length() > 0 )
                {
                  System.out.print( result[0] );
                }
                if( result[1] != null && result[1].length() > 0 )
                {
                  System.err.print( result[1] );
                }
              }

              public String[] evaluate( String strScript )
              {
                IGosuParser scriptParser = GosuParserFactory.createParser(strScript);

                try
                {
                  IGosuProgramParser programParser = GosuParserFactory.createProgramParser();
                  ParserOptions options = new ParserOptions().withParser( scriptParser );
                  IParseResult parseResult = programParser.parseExpressionOrProgram( strScript, scriptParser.getSymbolTable(), options );
                  Object result = parseResult.getProgram().evaluate( null );
                  if( result != null )
                  {
                    System.out.println( "Return Value: " + CoercionUtil.convertValue(result, JavaTypes.STRING()) );
                  }
                }
                catch( Exception e )
                {
                  boolean print = true;
                  Throwable t = e;
                  while( t != null )
                  {
                    if( t instanceof SystemExitIgnoredException)
                    {
                      print = false;
                    }
                    t = t.getCause();
                  }
                  if( print )
                  {
                    assert e != null;
                    e.printStackTrace();
                  }
                }
                return new String[]{null, null};
              }
            }, CLASS_REDEFINER_THREAD);
    sneakyDebugThread.setDaemon(true);
    sneakyDebugThread.start();
  }

  public static List<IDirectory> createDefaultClassPath( ) {
    List<String> vals = new ArrayList<String>();
    vals.add(System.getProperty("java.class.path", ""));
    vals.add(CommonServices.getEntityAccess().getWebServerPaths());
    vals.addAll(getJarsContainingSpecialClasses());
    vals.add(System.getProperty("sun.boot.class.path", ""));
    vals.add(System.getProperty("java.ext.dirs", ""));
    vals.add(CommonServices.getEntityAccess().getPluginRepositories().toString());

    return expand(vals);
  }

  private static List<IDirectory> expand( List<String> paths )
  {
    LinkedHashSet<IDirectory> expanded = new LinkedHashSet<IDirectory>();
    for( String path : paths )
    {
      for( String pathElement : path.split( File.pathSeparator ) )
      {
        if( pathElement.length() > 0 )
        {
          IDirectory resource = CommonServices.getFileSystem().getIDirectory(new File(pathElement));
          expanded.add(resource);
        }
      }
    }
    return new ArrayList<IDirectory>( expanded );
  }

  /**
   * This method is a hack to resolve "special" system-like classes provided by execution environment.
   * This is the replacement of old addSpecialJars() method
   */
  private static Set<String> getJarsContainingSpecialClasses() {
    Set<String> paths = new HashSet<String>();
    for (String className : SPECIAL_CLASSES) {
      getLogger().debug("Searching JAR that provides " + className + ".");
      Class<?> clazz;
      try {
        clazz = Class.forName(className);
      } catch (ClassNotFoundException e) {
        if( !ILanguageLevel.Util.STANDARD_GOSU() ) {
          getLogger().error("Class " + className
                  + " could not be found. Gosu code might fail to compile at runtime.");
        }
        continue;
      }
      CodeSource codeSource = clazz.getProtectionDomain().getCodeSource();
      if (codeSource == null) {
        if( !ILanguageLevel.Util.STANDARD_GOSU() ) {
          getLogger().error("Code source for " + clazz.getName()
                  + " is null. Gosu code might fail to compile at runtime.");
        }
        continue;
      }
      // url might be jar:<url>!/, e.g. jar:file:/gitmo/jboss-5.1.2/common/lib/servlet-api.jar!/
      // or vfszip:<url> on JBoss
      // or wsjar:<url> on WebSphere
      URL jarUrl = codeSource.getLocation();

      // in case of complex URL the path might be like this: "file:/gitmo/jboss-5.1.2/common/lib/servlet-api.jar!/"
      String path = jarUrl.getPath();

      // So removing optional "!/" suffix and "file:" prefix
      if (path.endsWith("/")) {
        path = path.substring(0, path.length() - 1);
      }
      if (path.endsWith("!")) {
        path = path.substring(0, path.length() - 1);
      }
      if (path.startsWith("file:")) {
        path = path.substring("file:".length());
      }

      // URLDecoder.decode() decodes string from application/x-www-form-urlencoded MIME format
      // while we need to decode from RFC2396 format.
      // I think the only difference between formats that application/x-www-form-urlencoded decodes "+"
      // to space while RFC2396 does not.
      // So before using URLDecoder.decode() encode "+" to its ASCII representation
      // that will be decoded back to "+" by URLDecoder.decode()
      path = path.replaceAll("\\+", "%2B");
      try {
        String decodedPath = URLDecoder.decode(path, "UTF-8");
        if (new File(decodedPath).exists()) {
          paths.add(path);
        } else {
          getLogger().error("Could not extract filesystem path from the url " + jarUrl.getPath()
                  + ". Gosu code that requires classes from that JAR might fail to compile at runtime.");
        }
      } catch (UnsupportedEncodingException ex) {
        // impossible
        throw GosuExceptionUtil.forceThrow(ex);
      }
    }
    return paths;
  }

  private static ILogger getLogger() {
    return CommonServices.getEntityAccess().getLogger();
  }

}
