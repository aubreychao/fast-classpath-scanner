/*
 * This file is part of FastClasspathScanner.
 * 
 * Author: Harith Elrufaie
 * 
 * Hosted at: https://github.com/lukehutch/fast-classpath-scanner
 * 
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2017 Harith Elrufaie
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without
 * limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so, subject to the following
 * conditions:
 * 
 * The above copyright notice and this permission notice shall be included in all copies or substantial
 * portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO
 * EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE
 * OR OTHER DEALINGS IN THE SOFTWARE.
 */
package io.github.lukehutch.fastclasspathscanner.classloaderhandler;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.lukehutch.fastclasspathscanner.scanner.ClasspathFinder;
import io.github.lukehutch.fastclasspathscanner.utils.LogNode;
import io.github.lukehutch.fastclasspathscanner.utils.ReflectionUtils;

/**
 * Custom Class Loader Handler for OSGi Felix ClassLoader.
 *
 * The handler adds the bundle jar and all assocaited Bundle-Claspath jars into the {@link ClasspathFinder} scan
 * classpath.
 *
 * @author elrufaie
 */
public class FelixClassLoaderHandler implements ClassLoaderHandler {

    final Set<Object> bundles = new HashSet<>();

    @Override
    public boolean handle(final ClassLoader classLoader, final ClasspathFinder classpathFinder, final LogNode log)
            throws Exception {
        final List<ClassLoader> classLoaders = Arrays.asList(classLoader);
        for (Class<?> c = classLoader.getClass(); c != null; c = c.getSuperclass()) {
            if ("org.apache.felix.framework.BundleWiringImpl$BundleClassLoaderJava5".equals(c.getName())
                    || "org.apache.felix.framework.BundleWiringImpl$BundleClassLoader".equals(c.getName())) {

                // Get the wiring for the ClassLoader's bundle
                final Object bundleWiring = ReflectionUtils.getFieldVal(classLoader, "m_wiring");
                addBundle(bundleWiring, classLoaders, classpathFinder, log);

                /*
                 * Finally, deal with any other bundles we might be wired to.
                 * Ideally we'd use the ScanSpec to narrow down the list of wires that we follow,
                 * but it doesn't seem to be available to us :-(
                 */

                final List<?> requiredWires = (List<?>) ReflectionUtils.invokeMethod(bundleWiring,
                        "getRequiredWires", String.class, null);
                if (requiredWires != null) {
                    for (final Object wire : requiredWires) {
                        final Object provider = ReflectionUtils.invokeMethod(wire, "getProviderWiring");
                        if (!bundles.contains(provider)) {
                            addBundle(provider, classLoaders, classpathFinder, log);
                        }
                    }
                }

                return true;
            }
        }
        return false;
    }

    private void addBundle(final Object bundleWiring, final List<ClassLoader> classLoaders,
            final ClasspathFinder classpathFinder, final LogNode log) throws Exception {
        // Track the bundles we've processed to prevent loops
        bundles.add(bundleWiring);

        // Get the revision for this wiring
        final Object revision = ReflectionUtils.invokeMethod(bundleWiring, "getRevision");
        // Get the contents
        final Object content = ReflectionUtils.invokeMethod(revision, "getContent");
        final String location = content != null ? getContentLocation(content) : null;
        if (location != null) {
            // Add the bundle object
            classpathFinder.addClasspathElement(location, classLoaders, log);

            // And any embedded content
            final List<?> embeddedContent = (List<?>) ReflectionUtils.invokeMethod(revision, "getContentPath");
            if (embeddedContent != null) {
                for (final Object embedded : embeddedContent) {
                    if (embedded != content) {
                        final String embeddedLocation = embedded != null ? getContentLocation(embedded) : null;
                        if (embeddedLocation != null) {
                            classpathFinder.addClasspathElement(embeddedLocation, classLoaders, log);
                        }
                    }
                }
            }
        }
    }

    private String getContentLocation(final Object content) throws Exception {
        final File file = (File) ReflectionUtils.invokeMethod(content, "getFile");
        return file != null ? file.toURI().toString() : null;
    }
}