/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.interestinglab.waterdrop.config.impl;

import io.github.interestinglab.waterdrop.config.ConfigException;
import io.github.interestinglab.waterdrop.config.ConfigOrigin;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

final class PropertiesParser {
    static AbstractConfigObject parse(Reader reader,
                                      ConfigOrigin origin) throws IOException {
        Properties props = new Properties();
        props.load(reader);
        return fromProperties(origin, props);
    }

    static String lastElement(String path) {
        int i = path.lastIndexOf('.');
        if (i < 0) {
            return path;
        }
        return path.substring(i + 1);
    }

    static String exceptLastElement(String path) {
        int i = path.lastIndexOf('.');
        if (i < 0) {
            return null;
        }
        return path.substring(0, i);
    }

    static Path pathFromPropertyKey(String key) {
        String last = lastElement(key);
        String exceptLast = exceptLastElement(key);
        Path path = new Path(last, null);
        while (exceptLast != null) {
            last = lastElement(exceptLast);
            exceptLast = exceptLastElement(exceptLast);
            path = new Path(last, path);
        }
        return path;
    }

    static AbstractConfigObject fromProperties(ConfigOrigin origin,
            Properties props) {
        return fromEntrySet(origin, props.entrySet());
    }

    private static <K, V> AbstractConfigObject fromEntrySet(ConfigOrigin origin, Set<Map.Entry<K, V>> entries) {
        final Map<Path, Object> pathMap = getPathMap(entries);
        return fromPathMap(origin, pathMap, true /* from properties */);
    }

    private static <K, V> Map<Path, Object> getPathMap(Set<Map.Entry<K, V>> entries) {
        Map<Path, Object> pathMap = new LinkedHashMap<Path, Object>();
        for (Map.Entry<K, V> entry : entries) {
            Object key = entry.getKey();
            if (key instanceof String) {
                Path path = pathFromPropertyKey((String) key);
                pathMap.put(path, entry.getValue());
            }
        }
        return pathMap;
    }

    static AbstractConfigObject fromStringMap(ConfigOrigin origin, Map<String, String> stringMap) {
        return fromEntrySet(origin, stringMap.entrySet());
    }

    static AbstractConfigObject fromPathMap(ConfigOrigin origin,
            Map<?, ?> pathExpressionMap) {
        Map<Path, Object> pathMap = new LinkedHashMap<Path, Object>();
        for (Map.Entry<?, ?> entry : pathExpressionMap.entrySet()) {
            Object keyObj = entry.getKey();
            if (!(keyObj instanceof String)) {
                throw new ConfigException.BugOrBroken(
                        "Map has a non-string as a key, expecting a path expression as a String");
            }
            Path path = Path.newPath((String) keyObj);
            pathMap.put(path, entry.getValue());
        }
        return fromPathMap(origin, pathMap, false /* from properties */);
    }

    private static AbstractConfigObject fromPathMap(ConfigOrigin origin,
                                                    Map<Path, Object> pathMap, boolean convertedFromProperties) {
        /*
         * First, build a list of paths that will have values, either string or
         * object values.
         */
        Set<Path> scopePaths = new HashSet<Path>();
        Set<Path> valuePaths = new HashSet<Path>();
        for (Path path : pathMap.keySet()) {
            // add value's path
            valuePaths.add(path);

            // all parent paths are objects
            Path next = path.parent();
            while (next != null) {
                scopePaths.add(next);
                next = next.parent();
            }
        }

        if (convertedFromProperties) {
            /*
             * If any string values are also objects containing other values,
             * drop those string values - objects "win".
             */
            valuePaths.removeAll(scopePaths);
        } else {
            /* If we didn't start out as properties, then this is an error. */
            for (Path path : valuePaths) {
                if (scopePaths.contains(path)) {
                    throw new ConfigException.BugOrBroken(
                            "In the map, path '"
                                    + path.render()
                                    + "' occurs as both the parent object of a value and as a value. "
                                    + "Because Map has no defined ordering, this is a broken situation.");
                }
            }
        }

        /*
         * Create maps for the object-valued values.
         */
        Map<String, AbstractConfigValue> root = new LinkedHashMap<String, AbstractConfigValue>();
        Map<Path, Map<String, AbstractConfigValue>> scopes = new LinkedHashMap<Path, Map<String, AbstractConfigValue>>();

        for (Path path : scopePaths) {
            Map<String, AbstractConfigValue> scope = new LinkedHashMap<String, AbstractConfigValue>();
            scopes.put(path, scope);
        }

        /* Store string values in the associated scope maps */
        for (Path path : valuePaths) {
            Path parentPath = path.parent();
            Map<String, AbstractConfigValue> parent = parentPath != null ? scopes
                    .get(parentPath) : root;

            String last = path.last();
            Object rawValue = pathMap.get(path);
            AbstractConfigValue value;
            if (convertedFromProperties) {
                if (rawValue instanceof String) {
                    value = new ConfigString.Quoted(origin, (String) rawValue);
                } else {
                    // silently ignore non-string values in Properties
                    value = null;
                }
            } else {
                value = ConfigImpl.fromAnyRef(pathMap.get(path), origin,
                        FromMapMode.KEYS_ARE_PATHS);
            }
            if (value != null) {
                parent.put(last, value);
            }
        }

        /*
         * Make a list of scope paths from longest to shortest, so children go
         * before parents.
         */
        List<Path> sortedScopePaths = new ArrayList<Path>();
        sortedScopePaths.addAll(scopePaths);
        // sort descending by length
        Collections.sort(sortedScopePaths, new Comparator<Path>() {
            @Override
            public int compare(Path a, Path b) {
                // Path.length() is O(n) so in theory this sucks
                // but in practice we can make Path precompute length
                // if it ever matters.
                return b.length() - a.length();
            }
        });

        /*
         * Create ConfigObject for each scope map, working from children to
         * parents to avoid modifying any already-created ConfigObject. This is
         * where we need the sorted list.
         */
        for (Path scopePath : sortedScopePaths) {
            Map<String, AbstractConfigValue> scope = scopes.get(scopePath);

            Path parentPath = scopePath.parent();
            Map<String, AbstractConfigValue> parent = parentPath != null ? scopes
                    .get(parentPath) : root;

            AbstractConfigObject o = new SimpleConfigObject(origin, scope,
                    ResolveStatus.RESOLVED, false /* ignoresFallbacks */);
            parent.put(scopePath.last(), o);
        }

        // return root config object
        return new SimpleConfigObject(origin, root, ResolveStatus.RESOLVED,
                false /* ignoresFallbacks */);
    }
}
