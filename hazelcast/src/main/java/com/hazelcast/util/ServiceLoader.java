/*
 * Copyright (c) 2008-2017, Hazelcast, Inc. All Rights Reserved.
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

package com.hazelcast.util;

import com.hazelcast.core.HazelcastException;
import com.hazelcast.logging.ILogger;
import com.hazelcast.logging.Logger;
import com.hazelcast.nio.ClassLoaderUtil;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import static com.hazelcast.nio.IOUtil.closeResource;
import static com.hazelcast.util.EmptyStatement.ignore;
import static com.hazelcast.util.Preconditions.isNotNull;

/**
 * Support class for loading Hazelcast services and hooks based on the Java {@link ServiceLoader} specification,
 * but changed in the fact of classloaders to test for given services to work in multi classloader
 * environments like application or OSGi servers.
 */
public final class ServiceLoader {

    private static final ILogger LOGGER = Logger.getLogger(ServiceLoader.class);
    private static final String FILTERING_CLASS_LOADER = FilteringClassLoader.class.getCanonicalName();

    // see https://github.com/hazelcast/hazelcast/issues/3922
    private static final String IGNORED_GLASSFISH_MAGIC_CLASSLOADER =
            "com.sun.enterprise.v3.server.APIClassLoaderServiceImpl$APIClassLoader";

    private ServiceLoader() {
    }

    public static <T> T load(Class<T> clazz, String factoryId, ClassLoader classLoader) throws Exception {
        Iterator<T> iterator = iterator(clazz, factoryId, classLoader);
        if (iterator.hasNext()) {
            return iterator.next();
        }
        return null;
    }

    public static <T> Iterator<T> iterator(Class<T> clazz, String factoryId, ClassLoader classLoader) throws Exception {
        Set<ServiceDefinition> serviceDefinitions = getServiceDefinitions(factoryId, classLoader);
        return new NewInstanceIterator<T>(serviceDefinitions, clazz);
    }

    public static <T> Iterator<Class<T>> classIterator(String factoryId, ClassLoader classLoader) throws Exception {
        Set<ServiceDefinition> serviceDefinitions = getServiceDefinitions(factoryId, classLoader);
        return new LoadClassIterator<T>(serviceDefinitions);
    }

    private static Set<ServiceDefinition> getServiceDefinitions(String factoryId, ClassLoader classLoader) {
        List<ClassLoader> classLoaders = selectClassLoaders(classLoader);

        Set<URLDefinition> factoryUrls = new HashSet<URLDefinition>();
        for (ClassLoader selectedClassLoader : classLoaders) {
            factoryUrls.addAll(collectFactoryUrls(factoryId, selectedClassLoader));
        }

        Set<ServiceDefinition> serviceDefinitions = new HashSet<ServiceDefinition>();
        for (URLDefinition urlDefinition : factoryUrls) {
            serviceDefinitions.addAll(parse(urlDefinition));
        }
        if (serviceDefinitions.isEmpty()) {
            Logger.getLogger(ServiceLoader.class).finest(
                    "Service loader could not load 'META-INF/services/" + factoryId + "' It may be empty or does not exist.");
        }
        return serviceDefinitions;
    }

    private static Set<URLDefinition> collectFactoryUrls(String factoryId, ClassLoader classLoader) {
        String resourceName = "META-INF/services/" + factoryId;
        try {
            Enumeration<URL> configs;
            if (classLoader != null) {
                configs = classLoader.getResources(resourceName);
            } else {
                configs = ClassLoader.getSystemResources(resourceName);
            }

            Set<URLDefinition> urlDefinitions = new HashSet<URLDefinition>();
            while (configs.hasMoreElements()) {
                URL url = configs.nextElement();
                URI uri = new URI(url.toExternalForm().replace(" ", "%20"));

                ClassLoader highestClassLoader = findHighestReachableClassLoader(url, classLoader, resourceName);
                if (!highestClassLoader.getClass().getName().equals(IGNORED_GLASSFISH_MAGIC_CLASSLOADER)) {
                    urlDefinitions.add(new URLDefinition(uri, highestClassLoader));
                }
            }
            return urlDefinitions;

        } catch (Exception e) {
            LOGGER.severe(e);
        }
        return Collections.emptySet();
    }

    private static Set<ServiceDefinition> parse(URLDefinition urlDefinition) {
        try {
            Set<ServiceDefinition> names = new HashSet<ServiceDefinition>();
            BufferedReader r = null;
            try {
                URL url = urlDefinition.uri.toURL();
                r = new BufferedReader(new InputStreamReader(url.openStream(), "UTF-8"));
                while (true) {
                    String line = r.readLine();
                    if (line == null) {
                        break;
                    }
                    int comment = line.indexOf('#');
                    if (comment >= 0) {
                        line = line.substring(0, comment);
                    }
                    String name = line.trim();
                    if (name.length() == 0) {
                        continue;
                    }
                    names.add(new ServiceDefinition(name, urlDefinition.classLoader));
                }
            } finally {
                closeResource(r);
            }
            return names;
        } catch (Exception e) {
            LOGGER.severe(e);
        }
        return Collections.emptySet();
    }

    private static ClassLoader findHighestReachableClassLoader(URL url, ClassLoader classLoader, String resourceName) {
        if (classLoader.getParent() == null) {
            return classLoader;
        }

        ClassLoader highestClassLoader = classLoader;

        ClassLoader current = classLoader;
        while (current.getParent() != null) {
            // if we have a filtering classloader in hierarchy, we need to stop!
            if (FILTERING_CLASS_LOADER.equals(current.getClass().getCanonicalName())) {
                break;
            }

            ClassLoader parent = current.getParent();
            try {
                Enumeration<URL> resources = parent.getResources(resourceName);
                if (resources != null) {
                    while (resources.hasMoreElements()) {
                        URL resourceURL = resources.nextElement();
                        if (url.toURI().equals(resourceURL.toURI())) {
                            highestClassLoader = parent;
                        }
                    }
                }
            } catch (IOException ignore) {
                // we want to ignore failures and keep searching
                ignore(ignore);
            } catch (URISyntaxException ignore) {
                // we want to ignore failures and keep searching
                ignore(ignore);
            }

            // going on with the search upwards the hierarchy
            current = current.getParent();
        }
        return highestClassLoader;
    }

    static List<ClassLoader> selectClassLoaders(ClassLoader classLoader) {
        // list prevents reordering!
        List<ClassLoader> classLoaders = new ArrayList<ClassLoader>();

        if (classLoader != null) {
            classLoaders.add(classLoader);
        }

        // check if TCCL is same as given classLoader
        ClassLoader tccl = Thread.currentThread().getContextClassLoader();
        if (tccl != classLoader) {
            classLoaders.add(tccl);
        }

        // Hazelcast core classLoader
        ClassLoader coreClassLoader = ServiceLoader.class.getClassLoader();
        if (coreClassLoader != classLoader && coreClassLoader != tccl) {
            classLoaders.add(coreClassLoader);
        }

        // Hazelcast client classLoader
        try {
            Class<?> hzClientClass = Class.forName("com.hazelcast.client.HazelcastClient");
            ClassLoader clientClassLoader = hzClientClass.getClassLoader();
            if (clientClassLoader != classLoader && clientClassLoader != tccl && clientClassLoader != coreClassLoader) {
                classLoaders.add(clientClassLoader);
            }

        } catch (ClassNotFoundException ignore) {
            // ignore since we does not have HazelcastClient in classpath
            ignore(ignore);
        }

        return classLoaders;
    }

    /**
     * Definition of the internal service based on classloader that is able to load it
     * and the classname of the found service.
     */
    private static final class ServiceDefinition {

        private final String className;
        private final ClassLoader classLoader;

        private ServiceDefinition(String className, ClassLoader classLoader) {
            this.className = isNotNull(className, "className");
            this.classLoader = isNotNull(classLoader, "classLoader");
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            ServiceDefinition that = (ServiceDefinition) o;
            if (!classLoader.equals(that.classLoader)) {
                return false;
            }
            if (!className.equals(that.className)) {
                return false;
            }
            return true;
        }

        @Override
        public int hashCode() {
            int result = className.hashCode();
            result = 31 * result + classLoader.hashCode();
            return result;
        }
    }

    /**
     * This class keeps track of available service definition URLs and
     * the corresponding classloaders.
     */
    private static final class URLDefinition {

        private final URI uri;
        private final ClassLoader classLoader;

        private URLDefinition(URI url, ClassLoader classLoader) {
            this.uri = url;
            this.classLoader = classLoader;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            URLDefinition that = (URLDefinition) o;
            if (uri != null ? !uri.equals(that.uri) : that.uri != null) {
                return false;
            }
            return true;
        }

        @Override
        public int hashCode() {
            return uri == null ? 0 : uri.hashCode();
        }
    }

    private static class NewInstanceIterator<T> implements Iterator<T> {

        private final Iterator<ServiceDefinition> iterator;
        private final Class<T> clazz;

        NewInstanceIterator(Set<ServiceDefinition> serviceDefinitions, Class<T> clazz) {
            this.iterator = serviceDefinitions.iterator();
            this.clazz = clazz;
        }

        @Override
        public boolean hasNext() {
            return iterator.hasNext();
        }

        @Override
        public T next() {
            ServiceDefinition definition = iterator.next();
            try {
                String className = definition.className;
                ClassLoader classLoader = definition.classLoader;
                return clazz.cast(ClassLoaderUtil.newInstance(classLoader, className));
            } catch (Exception e) {
                throw new HazelcastException(e);
            }
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }
    }

    private static class LoadClassIterator<T> implements Iterator<Class<T>> {

        private final Iterator<ServiceDefinition> iterator;

        LoadClassIterator(Set<ServiceDefinition> serviceDefinitions) {
            iterator = serviceDefinitions.iterator();
        }

        @Override
        public boolean hasNext() {
            return iterator.hasNext();
        }

        @Override
        @SuppressWarnings("unchecked")
        public Class<T> next() {
            ServiceDefinition definition = iterator.next();
            try {
                String className = definition.className;
                ClassLoader classLoader = definition.classLoader;
                return (Class<T>) ClassLoaderUtil.loadClass(classLoader, className);
            } catch (Exception e) {
                throw new HazelcastException(e);
            }
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }
    }
}
