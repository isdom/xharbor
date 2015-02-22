package org.jocean.xharbor;

import java.util.Map;

public interface UnitAdminMXBean {

    public boolean newUnit(
            final String unitName, 
            final String template, 
            final String[] unitParameters);

    public boolean newUnit(
            final String unitName, 
            final String template, 
            final Map<String, String> unitParameters);

    public boolean deleteUnit(final String unitName);

    public void deleteAllUnit();

    public Map<String, String[]> getSourceInfo(final String template);

    public String[] getLogs();

    public void resetLogs();
}
