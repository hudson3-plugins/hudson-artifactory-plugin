/*
 * Copyright (C) 2010 JFrog Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jfrog.hudson.plugins.artifactory.config;

import com.google.common.base.Function;
import com.google.common.collect.Lists;
import hudson.model.BuildListener;
import hudson.model.Hudson;
import org.apache.commons.lang.StringUtils;
import org.jfrog.build.api.util.NullLog;
import org.jfrog.build.client.*;
import org.jfrog.hudson.plugins.artifactory.util.HudsonBuildInfoLog;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.IOException;
import java.io.Serializable;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Represents an artifactory instance.
 *
 * @author Yossi Shaul
 */
public class ArtifactoryServer implements Serializable {
    private static final Logger log = Logger.getLogger(ArtifactoryServer.class.getName());

    private static final int DEFAULT_CONNECTION_TIMEOUT = 300;    // 5 Minutes

    private final String url;
    private final String id;

    private final Credentials deployerCredentials;
    private Credentials resolverCredentials;

    // Network timeout in seconds to use both for connection establishment and for unanswered requests
    private int timeout = DEFAULT_CONNECTION_TIMEOUT;
    private boolean bypassProxy;

    /**
     * List of repository keys, last time we checked. Copy on write semantics.
     */
    private transient volatile List<String> repositories;

    private transient volatile List<VirtualRepository> virtualRepositories;

    @DataBoundConstructor
    public ArtifactoryServer(String serverId, String url, Credentials deployerCredentials, Credentials resolverCredentials, int timeout,
                             boolean bypassProxy) {
        this.url = StringUtils.removeEnd(url, "/");
        this.deployerCredentials = deployerCredentials;
        this.resolverCredentials = resolverCredentials;
        this.timeout = timeout > 0 ? timeout : DEFAULT_CONNECTION_TIMEOUT;
        this.bypassProxy = bypassProxy;
        this.id = serverId == null || serverId.isEmpty() ? url.hashCode() + "@" + System.currentTimeMillis() : serverId;
    }

    public String getName() {
        return id != null ? id : url;
    }

    public String getUrl() {
        return url;
    }

    public Credentials getDeployerCredentials() {
        return deployerCredentials;
    }

    public Credentials getResolverCredentials() {
        return resolverCredentials;
    }

    public int getTimeout() {
        return timeout;
    }

    public boolean isBypassProxy() {
        return bypassProxy;
    }

    public List<String> getRepositoryKeys() {
        Credentials resolvingCredentials = getResolvingCredentials();
        ArtifactoryBuildInfoClient client = createArtifactoryClient(resolvingCredentials.getUsername(),
                resolvingCredentials.getPassword(), createProxyConfiguration(Hudson.getInstance().proxy));
        try {
            repositories = client.getLocalRepositoriesKeys();
        } catch (IOException e) {
            if (log.isLoggable(Level.FINE)) {
                log.log(Level.WARNING, "Could not obtain local repositories list from '" + url + "'", e);
            } else {
                log.log(Level.WARNING,
                        "Could not obtain local repositories list from '" + url + "': " + e.getMessage());
            }
            return Lists.newArrayList();
        } finally {
            client.shutdown();
        }
        return repositories;
    }

    public List<String> getReleaseRepositoryKeysFirst() {
        List<String> repositoryKeys = getRepositoryKeys();
        if (repositoryKeys == null || repositoryKeys.isEmpty()) {
            return Lists.newArrayList();
        }
        Collections.sort(repositoryKeys, new RepositoryComparator());
        return repositoryKeys;
    }

    public List<String> getSnapshotRepositoryKeysFirst() {
        List<String> repositoryKeys = getRepositoryKeys();
        if (repositoryKeys == null || repositoryKeys.isEmpty()) {
            return Lists.newArrayList();
        }
        Collections.sort(repositoryKeys, Collections.reverseOrder(new RepositoryComparator()));
        return repositoryKeys;
    }

    private static class RepositoryComparator implements Comparator<String>, Serializable {

        public int compare(String o1, String o2) {
            if (o1.contains("snapshot") && !o2.contains("snapshot")) {
                return 1;
            } else {
                return -1;
            }
        }
    }

    public List<VirtualRepository> getVirtualRepositoryKeys() {
        Credentials resolvingCredentials = getResolvingCredentials();
        ArtifactoryBuildInfoClient client = createArtifactoryClient(resolvingCredentials.getUsername(),
                resolvingCredentials.getPassword(), createProxyConfiguration(Hudson.getInstance().proxy));
        try {
            List<String> keys = client.getVirtualRepositoryKeys();
            virtualRepositories = Lists.newArrayList(Lists.transform(keys, new Function<String, VirtualRepository>() {
                public VirtualRepository apply(String from) {
                    return new VirtualRepository(from, from);
                }
            }));
        } catch (IOException e) {
            if (log.isLoggable(Level.FINE)) {
                log.log(Level.WARNING, "Could not obtain virtual repositories list from '" + url + "'", e);
            } else {
                log.log(Level.WARNING,
                        "Could not obtain virtual repositories list from '" + url + "': " + e.getMessage());
            }
            return Lists.newArrayList();
        } finally {
            client.shutdown();
        }
        virtualRepositories
                .add(0, new VirtualRepository("-- To use Artifactory for resolution select a virtual repository --",
                        ""));
        return virtualRepositories;
    }

    public boolean isArtifactoryPro() {
        Credentials resolvingCredentials = getResolvingCredentials();
        try {
            ArtifactoryHttpClient client = new ArtifactoryHttpClient(url, resolvingCredentials.getUsername(),
                    resolvingCredentials.getPassword(), new NullLog());
            ArtifactoryVersion version = client.getVersion();
            return version.hasAddons();
        } catch (IOException e) {
            if (log.isLoggable(Level.FINE)) {
                log.log(Level.WARNING, "Could not obtain artifactory version from '" + url + "'", e);
            } else {
                log.log(Level.WARNING,
                        "Could not obtain artifactory version from '" + url + "': " + e.getMessage());
            }
        }
        return false;
    }

    /**
     * This method might run on slaves, this is why we provide it with a proxy from the master config
     */
    public ArtifactoryBuildInfoClient createArtifactoryClient(String userName, String password,
                                                              ProxyConfiguration proxyConfiguration) {
        ArtifactoryBuildInfoClient client = new ArtifactoryBuildInfoClient(url, userName, password, new NullLog());
        client.setConnectionTimeout(timeout);
        if (!bypassProxy && proxyConfiguration != null) {
            client.setProxyConfiguration(proxyConfiguration.host,
                    proxyConfiguration.port,
                    proxyConfiguration.username,
                    proxyConfiguration.password);
        }

        return client;
    }

    public ProxyConfiguration createProxyConfiguration(hudson.ProxyConfiguration proxy) {
        ProxyConfiguration proxyConfiguration = null;
        if (!(proxy == null || proxy.getName() == null)) {
            proxyConfiguration = new ProxyConfiguration();
            proxyConfiguration.host = proxy.name;
            proxyConfiguration.port = proxy.port;
            proxyConfiguration.username = proxy.getUserName();
            proxyConfiguration.password = proxy.getPassword();
        }

        return proxyConfiguration;
    }


    /**
     * This method might run on slaves, this is why we provide it with a proxy from the master config
     */
    public ArtifactoryDependenciesClient createArtifactoryDependenciesClient(String userName, String password,
                                                                             ProxyConfiguration proxyConfiguration, BuildListener listener) {
        ArtifactoryDependenciesClient client = new ArtifactoryDependenciesClient(url, userName, password,
                new HudsonBuildInfoLog(listener));
        client.setConnectionTimeout(timeout);
        if (!bypassProxy && proxyConfiguration != null) {
            client.setProxyConfiguration(proxyConfiguration.host, proxyConfiguration.port, proxyConfiguration.username,
                    proxyConfiguration.password);
        }

        return client;
    }

    /**
     * Decides what are the preferred credentials to use for resolving the repo keys of the server
     *
     * @return Preferred credentials for repo resolving. Never null.
     */
    public Credentials getResolvingCredentials() {
        if (getResolverCredentials() != null) {
            return getResolverCredentials();
        }

        if (getDeployerCredentials() != null) {
            return getDeployerCredentials();
        }

        return new Credentials(null, null);
    }

}