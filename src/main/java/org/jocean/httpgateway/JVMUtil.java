package org.jocean.httpgateway;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * @author hp
 */
public class JVMUtil {
    public static boolean appendtoClassPath(String name) {
        //	适用于 JDK 1.6+
        //	from JDK DOC "java.lang.instrument Interface Instrumentation"
        //	...
        //	The system class loader supports adding a JAR file to be searched
        //	if it implements a method named appendToClassPathForInstrumentation
        //	which takes a single parameter of type java.lang.String.
        //	The method is not required to have public access. The name of the JAR file
        //	is obtained by invoking the getName() method on the jarfile and this is
        //	provided as the parameter to the appendtoClassPathForInstrumentation method.
        //	...

        try {
            ClassLoader clsLoader = ClassLoader.getSystemClassLoader();
            Method appendToClassPathMethod = clsLoader.getClass().getDeclaredMethod(
                    "appendToClassPathForInstrumentation",
                    String.class);
            if (null != appendToClassPathMethod) {
                appendToClassPathMethod.setAccessible(true);
                appendToClassPathMethod.invoke(clsLoader, name);
            }
            return true;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    public static String[] addAllJarsToClassPath(String dirName) {
        List<String> ret = new ArrayList<String>();

        File dir = new File(dirName);
        if (dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files == null) {
                return new String[0];
            }
            for (File file : files) {
                if (file.isDirectory()) {
                    ret.addAll(
                            Arrays.asList(addAllJarsToClassPath(file.getAbsolutePath())));
                } else {
                    String filename = file.getName().toLowerCase();
                    if (filename.endsWith(".jar")) {
                        if (appendtoClassPath(file.getAbsolutePath())) {
                            ret.add(file.getAbsolutePath());
                        }
                    }
                }
            }
        }

        return ret.toArray(new String[ret.size()]);
    }
}
