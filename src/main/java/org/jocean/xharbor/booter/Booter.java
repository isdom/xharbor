package org.jocean.xharbor.booter;

public class Booter {

    /**
     * @param args
     */
    public static void main(String[] args) {
        String[] extJars = JVMUtil.addAllJarsToClassPath(System.getProperty("user.dir") + "/lib");
        for (String jarName : extJars) {
            System.out.println("add path [" + jarName + "]");
        }

        try {
            ZKMain.main(args);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}
