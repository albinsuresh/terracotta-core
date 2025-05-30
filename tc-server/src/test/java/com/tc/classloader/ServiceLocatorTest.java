/*
 *  Copyright Terracotta, Inc.
 *  Copyright IBM Corp. 2024, 2025
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package com.tc.classloader;

import static com.tc.server.Directories.TC_INSTALL_ROOT_PROPERTY_NAME;

import com.tc.util.Assert;
import com.tc.util.ZipBuilder;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

/**
 *
 * @author mscott
 */
public class ServiceLocatorTest {
  
  @Rule
  public TemporaryFolder folder = new TemporaryFolder();
  
  public ServiceLocatorTest() {
  }
  
  @BeforeClass
  public static void setUpClass() {
  }
  
  @AfterClass
  public static void tearDownClass() {
  }
  
  @Before
  public void setUp() {
  }
  
  @After
  public void tearDown() {
  }

   @Test
   public void test() throws Exception {
     File f = folder.newFolder();
     File impl = new File(f,  "plugins/lib");
     File meta = new File(impl, "META-INF/services");
     meta.mkdirs();
     File api = new File(f,  "plugins/api");
     api.mkdirs();
     writeClass(impl, "com.tc.classloader.TestInterfaceImpl");
     writeClass(impl, "com.tc.classloader.TestInterfaceHandle");
     File testApi = writeZip(new File(api, "test.jar"), "com.tc.classloader.TestInterface");
     System.setProperty(TC_INSTALL_ROOT_PROPERTY_NAME, f.getAbsolutePath());
     
     ClassLoader apiLoader = new ApiClassLoader(new URL[] {testApi.toURI().toURL()}, null);
     ClassLoader testloader = new StrictURLClassLoader(new URL[] {impl.toURI().toURL()}, apiLoader, new AnnotationOrDirectoryStrategyChecker());
     
     FileOutputStream services1 = new FileOutputStream(new File(meta, "com.tc.classloader.TestInterface"));
     services1.write("com.tc.classloader.TestInterfaceImpl".getBytes());
     services1.close();
     FileOutputStream services2 = new FileOutputStream(new File(meta, "java.lang.Runnable"));
     services2.write("com.tc.classloader.TestInterfaceHandle".getBytes());
     services2.close();
     ComponentURLClassLoader component = new ComponentURLClassLoader(new URL[] {impl.toURI().toURL()}, 
         testloader, new AnnotationOrDirectoryStrategyChecker());
     Class<?> interf = component.loadClass("com.tc.classloader.TestInterface");
     Class<?> interi = component.loadClass("com.tc.classloader.TestInterfaceImpl");
     Assert.assertTrue(interf.getClassLoader() instanceof ApiClassLoader);
     Assert.assertEquals(interf.getClassLoader(), apiLoader);
     Assert.assertEquals(interi.getClassLoader(), component);
     
     Thread.currentThread().setContextClassLoader(apiLoader);
     Collection<Class<? extends Runnable>> list = new ServiceLocator(apiLoader).getImplementationsTypes(Runnable.class, apiLoader);
     for (Class<? extends Runnable> r : list) {
       r.newInstance().run();
     }
   }

   @Test
   public void testStrictMode() throws Exception {
     File f = folder.newFolder();
     File impl = new File(f, "plugins/lib");
     impl.mkdirs();
     File api = new File(f,  "plugins/api");
     api.mkdirs();
     File overload = writeZip(new File(impl, "overload.jar"), "com.tc.classloader.OverloadTestInterfaceImpl");
     File testImpl = writeZip(new File(impl, "impl.jar"), "com.tc.classloader.TestInterfaceImpl", "com.tc.classloader.TestInterfaceHandle");
     File testApi = writeZip(new File(api, "test.jar"), "com.tc.classloader.TestInterface");
     System.setProperty(TC_INSTALL_ROOT_PROPERTY_NAME, f.getAbsolutePath());

     ClassLoader apiLoader = new ApiClassLoader(new URL[] {testApi.toURI().toURL()}, null);
     ClassLoader testloader = new StrictURLClassLoader(new URL[] {overload.toURI().toURL(),testImpl.toURI().toURL()}, apiLoader, new AnnotationOrDirectoryStrategyChecker());
     ComponentURLClassLoader component = new ComponentURLClassLoader(new URL[] {overload.toURI().toURL()},
         testloader,new AnnotationOrDirectoryStrategyChecker());
     try {
      Class<?> interf = component.loadClass("com.tc.classloader.OverloadTestInterfaceImpl");
      Assert.fail("class should not load");
     } catch (NoClassDefFoundError err) {
       //
     }
     testloader = new StrictURLClassLoader(new URL[] {overload.toURI().toURL(),testImpl.toURI().toURL()}, apiLoader, new UniversalCommonComponentChecker());
     component = new ComponentURLClassLoader(new URL[] {overload.toURI().toURL()}, testloader,new AnnotationOrDirectoryStrategyChecker());
      try {
        Class<?> interf = component.loadClass("com.tc.classloader.OverloadTestInterfaceImpl");
        Assert.assertEquals(testloader, interf.getClassLoader());
      } catch (Error e) {
        e.printStackTrace();
      }
   }
   
   @Test
   public void testURLsFromZip() throws Exception {
     File base = folder.newFolder();
     File test = new File(base, "test.jar");
     ZipBuilder zip = new ZipBuilder(test, false);
     zip.putEntry("META-INF/services/com.tc.classloader.TestInterface", "com.tc.classloader.TestInterfaceImpl".getBytes());
     zip.putEntry("com/tc/classloader/TestInterfaceImpl.class", resourceToBytes("com/tc/classloader/TestInterfaceImpl.class"));
//  put it in the zip so null parent loader can find it
     zip.putEntry("com/tc/classloader/TestInterface.class", resourceToBytes("com/tc/classloader/TestInterface.class"));
     zip.finish();
     Collection<Class<?>> map = new ServiceLocator(new URLClassLoader(new URL[] {test.toURI().toURL()})).testingCheckUrls("com.tc.classloader.TestInterface");
     Assert.assertTrue(map.size() == 1);
   }
   
   @Test
   public void testURLsFromDir() throws Exception {
     File base = folder.newFolder();
     new File(base, "META-INF/services/").mkdirs();
     FileOutputStream meta = new FileOutputStream(new File(base, "META-INF/services/com.tc.classloader.TestInterface"));
     meta.write("com.tc.classloader.TestInterfaceImpl".getBytes());
     meta.close();
     new File(base, "com/tc/classloader/").mkdirs();
     FileOutputStream clazz = new FileOutputStream(new File(base, "com/tc/classloader/TestInterfaceImpl.class"));
     clazz.write(resourceToBytes("com/tc/classloader/TestInterfaceImpl.class"));
     clazz.close();
     
     FileOutputStream impl = new FileOutputStream(new File(base, "com/tc/classloader/TestInterface.class"));
     impl.write(resourceToBytes("com/tc/classloader/TestInterface.class"));
     impl.close();
     
     try {
       Collection<Class<?>> map = new ServiceLocator(new URLClassLoader(new URL[] {base.toURI().toURL()})).testingCheckUrls("com.tc.classloader.TestInterface");
       Assert.assertTrue(map.size() == 1);
     } catch (Throwable t) {
       t.printStackTrace();
       throw t;
     }
   }

   @Test
   public void testSingleSourceForServices() throws Exception {
     File base = folder.newFolder();
     new File(base, "META-INF/services/").mkdirs();
     FileOutputStream meta = new FileOutputStream(new File(base, "META-INF/services/com.tc.classloader.TestInterface"));
     meta.write("com.tc.classloader.TestInterfaceImpl".getBytes());
     meta.write("\n".getBytes());
     meta.write("com.tc.classloader.AlternativeTestInterfaceImpl".getBytes());
     meta.close();
     new File(base, "com/tc/classloader/").mkdirs();
     FileOutputStream clazz = new FileOutputStream(new File(base, "com/tc/classloader/TestInterfaceImpl.class"));
     clazz.write(resourceToBytes("com/tc/classloader/TestInterfaceImpl.class"));
     clazz.close();
     clazz = new FileOutputStream(new File(base, "com/tc/classloader/AlternativeTestInterfaceImpl.class"));
     clazz.write(resourceToBytes("com/tc/classloader/AlternativeTestInterfaceImpl.class"));
     clazz.close();
     FileOutputStream impl = new FileOutputStream(new File(base, "com/tc/classloader/TestInterface.class"));
     impl.write(resourceToBytes("com/tc/classloader/TestInterface.class"));
     impl.close();

     try {
       Collection<Class<?>> map = new ServiceLocator(new URLClassLoader(new URL[] {base.toURI().toURL()})).testingCheckUrls("com.tc.classloader.TestInterface");
       Assert.assertTrue(map.size() == 2);
       List<ClassLoader> cls = map.stream().map(Class::getClassLoader).collect(Collectors.toList());
       Assert.assertEquals(cls.get(0), cls.get(1));
     } catch (Throwable t) {
       t.printStackTrace();
       throw t;
     }
   }
   
   private File writeZip(File api, String...classes) throws IOException {
     ZipBuilder builder = new ZipBuilder(api, true);
     for (String className : classes) {
       className = className.replace('.', '/');
       builder.putEntry(className + ".class", resourceToBytes(className + ".class"));
     }
     builder.finish();
     return api;
   }
   
   private byte[] resourceToBytes(String loc) throws IOException {
     ByteArrayOutputStream fos = new ByteArrayOutputStream();
     InputStream implb = getClass().getClassLoader().getResourceAsStream(loc);
     int check = implb.read();
     while (check >= 0) {
       fos.write(check);
       check = implb.read();
     }
     fos.close();
     return fos.toByteArray();
   }
   
   private void writeClass(File base, String className)  throws IOException {
     className = className.replace('.', '/');
     int psplit = className.lastIndexOf('/');
     File impld = new File(base, className.substring(0, psplit));
     impld.mkdirs();
     FileOutputStream fos = new FileOutputStream(new File(impld, className.substring(psplit + 1) + ".class"));
     fos.write(resourceToBytes(className + ".class"));
     fos.close();
   }
}
