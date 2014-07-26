/*
 * Copyright 2014 Guidewire Software, Inc.
 */
package gw.internal.gosu.init;

import gw.config.ExecutionMode;
import gw.fs.IDirectory;
import gw.internal.gosu.parser.ExecutionEnvironment;
import gw.internal.gosu.parser.TypeLoaderAccess;
import gw.lang.gosuc.GosucModule;
import gw.lang.init.GosuPathEntry;
import gw.lang.parser.ITypeUsesMap;
import gw.lang.reflect.module.IExecutionEnvironment;
import gw.lang.reflect.module.IModule;

import java.util.Collection;
import java.util.List;

public class InternalGosuInit {

  // single module (i.e. runtime)

  public static void initializeRuntime( IExecutionEnvironment execEnv, List<? extends GosuPathEntry> pathEntries ) {
    ((ExecutionEnvironment)execEnv).initializeDefaultSingleModule(pathEntries);
  }

  public static void reinitializeRuntime( IExecutionEnvironment execEnv, List<? extends GosuPathEntry> pathEntries ) {
    ((ExecutionEnvironment)execEnv).initializeDefaultSingleModule( pathEntries );
    TypeLoaderAccess.instance().incrementChecksums();
  }

  public static void uninitializeRuntime( IExecutionEnvironment execEnv ) {
    ((ExecutionEnvironment)execEnv).uninitializeDefaultSingleModule();
  }

  public static void initializeCompiler(IExecutionEnvironment execEnv, GosucModule module) {
    ((ExecutionEnvironment)execEnv).initializeCompiler(module);
  }

  public static void uninitializeCompiler( IExecutionEnvironment execEnv ) {
    ((ExecutionEnvironment)execEnv).uninitializeCompiler();
  }
}
