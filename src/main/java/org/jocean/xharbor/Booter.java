package org.jocean.xharbor;

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
            Main.main(args);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}
