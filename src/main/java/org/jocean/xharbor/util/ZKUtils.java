/**
 * 
 */
package org.jocean.xharbor.util;

import java.util.Arrays;

import org.apache.curator.RetryPolicy;
import org.apache.curator.ensemble.exhibitor.ExhibitorEnsembleProvider;
import org.apache.curator.ensemble.exhibitor.Exhibitors;
import org.apache.curator.ensemble.exhibitor.Exhibitors.BackupConnectionStringProvider;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.jocean.idiom.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author isdom
 *
 */
public class ZKUtils {
    private static final Logger LOG = LoggerFactory
            .getLogger(ZKUtils.class);
    
    public static CuratorFramework buildWithExhibitorEnsembleProvider(
            final String[] exhibitorHostnames,
            final int exhibitorRestPort,
            final String backupConnectionString,
            final String basicAuthUser,
            final String basicAuthPass,
            final String restUriPath,
            final int pollingMs,
            final RetryPolicy retryPolicy) {
        final ExhibitorEnsembleProvider exhibitorEnsembleProvider =  new ExhibitorEnsembleProvider(
                new Exhibitors(Arrays.asList(exhibitorHostnames), 
                    exhibitorRestPort, 
                    new BackupConnectionStringProvider() {
                        @Override
                        public String getBackupConnectionString() throws Exception {
                            return backupConnectionString;
                        }}),
                (null != basicAuthUser && null != basicAuthPass
                  && !("".equals(basicAuthUser) && "".equals(basicAuthPass)) )
                    ? new DefaultExhibitorRestClientWithBasicAuth(basicAuthUser, basicAuthPass)
                    : new DefaultExhibitorRestClientWithBasicAuth(),
                restUriPath,
                pollingMs,
                retryPolicy);
        try {
            exhibitorEnsembleProvider.pollForInitialEnsemble();
        } catch (Exception e) {
            LOG.warn("exception when invoke pollForInitialEnsemble for {}, detail: {}",
                    exhibitorEnsembleProvider, ExceptionUtils.exception2detail(e));
        }
        return CuratorFrameworkFactory.builder().
            ensembleProvider(exhibitorEnsembleProvider)
            .sessionTimeoutMs(60 * 1000)
            .connectionTimeoutMs(15 * 1000)
            .retryPolicy(retryPolicy)
            .build();
    }
}
