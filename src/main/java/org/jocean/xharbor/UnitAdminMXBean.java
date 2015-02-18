package org.jocean.xharbor;

import java.util.Map;

public interface UnitAdminMXBean {

    public void newUnit(final String name, final String template, final String[] params) throws Exception;

    public void newUnit(final String name, final String template, final Map<String, String> params) throws Exception;

    public void deleteUnit(final String name);

    public void deleteAllUnit();

    public Map<String, Map<String, String>> getSourceInfo(final String template);

    public String[] getLogs();

    public void resetLogs();
}
