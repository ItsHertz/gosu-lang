/*
 * Copyright 2014 Guidewire Software, Inc.
 */

package gw.fs;

import gw.fs.jar.JarFileDirectoryImpl;
import gw.fs.physical.IPhysicalFileSystem;
import gw.fs.physical.PhysicalDirectoryImpl;
import gw.fs.physical.PhysicalFileImpl;
import gw.fs.physical.fast.FastPhysicalFileSystem;
import gw.lang.UnstableAPI;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;

@UnstableAPI
public class FileFactory {

  private static final FileFactory INSTANCE = new FileFactory();

  private IPhysicalFileSystem _defaultPhysicalFileSystem;

  private FileFactory() {
    _defaultPhysicalFileSystem = createDefaultPhysicalFileSystem();
  }

  public static FileFactory instance() {
    return INSTANCE;
  }

  public IDirectory getIDirectory(File f) {
    if (f.getName().endsWith(".jar") && f.isFile()) {
      return new JarFileDirectoryImpl(f);
    } else {
      return new PhysicalDirectoryImpl(ResourcePath.parse(f.getAbsolutePath()), _defaultPhysicalFileSystem);
    }
  }

  public IFile getIFile(File f) {
    return new PhysicalFileImpl(ResourcePath.parse(f.getAbsolutePath()), _defaultPhysicalFileSystem);
  }

  public IDirectory getIDirectory(String absolutePath) {
    if (absolutePath.endsWith(".jar") && new File(absolutePath).isFile()) {
      return new JarFileDirectoryImpl(new File(absolutePath));
    } else {
      return new PhysicalDirectoryImpl(ResourcePath.parse(absolutePath), _defaultPhysicalFileSystem);
    }
  }

  public IFile getIFile(String absolutePath) {
    return new PhysicalFileImpl(ResourcePath.parse(absolutePath), _defaultPhysicalFileSystem);
  }

  public IFile getIFile(URL url) {
    return getIFile( url, true );
  }
  public IFile getIFile(URL url, boolean bCreateIfNotExists) {
    if (url.getProtocol().equals("file")) {
      try {
        URI uri = url.toURI();
        if (uri.getFragment() != null) {
          uri = new URI(uri.getScheme(), uri.getSchemeSpecificPart(), null);
        }
        return getIFile(new File(uri));
      }
      catch (URISyntaxException ex) {
        throw new RuntimeException(ex);
      }
      catch (IllegalArgumentException ex) {
        // debug getting IAE only in TH - unable to parse URL with fragment identifier
        throw new IllegalArgumentException("Unable to parse URL " + url.toExternalForm(), ex);
      }
    } else if (url.getProtocol().equals("jar")) {
      String path = url.getPath();
      int idx = path.lastIndexOf('!');
      String filePath = path.substring(idx + 1);
      String jarPath = path.substring(0, idx);
      File jarFile;
      try {
        jarFile = getIFile(new URL(jarPath)).toJavaFile();
      }
      catch (MalformedURLException e) {
        throw new RuntimeException(e);
      }
      JarFileDirectoryImpl jarFileDirectory = new JarFileDirectoryImpl(jarFile);

      if( bCreateIfNotExists ) {
        return jarFileDirectory.getOrCreateFile( filePath );
      }
      return jarFileDirectory.file( filePath );
    } else {
      throw new RuntimeException("Unrecognized protocol: " + url.getProtocol());
    }
  }

  public IPhysicalFileSystem getDefaultPhysicalFileSystem() {
    return _defaultPhysicalFileSystem;
  }

  public IPhysicalFileSystem getRootPhysicalFileSystem() {
    return _defaultPhysicalFileSystem;
  }

  public void setDefaultPhysicalFileSystem(IPhysicalFileSystem fileSystem) {
    _defaultPhysicalFileSystem = fileSystem;
  }

  // ---------------------------- Private Implementation Methods

  private IPhysicalFileSystem createDefaultPhysicalFileSystem() {
    return new FastPhysicalFileSystem();
  }
}
