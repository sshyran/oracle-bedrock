/*
 * File: OperationalOverride.java
 *
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * The contents of this file are subject to the terms and conditions of
 * the Common Development and Distribution License 1.0 (the "License").
 *
 * You may not use this file except in compliance with the License.
 *
 * You can obtain a copy of the License by consulting the LICENSE.txt file
 * distributed with this file, or by consulting https://oss.oracle.com/licenses/CDDL
 *
 * See the License for the specific language governing permissions
 * and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file LICENSE.txt.
 *
 * MODIFICATIONS:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 */

package com.oracle.tools.runtime.coherence.options;

import com.oracle.tools.Option;
import com.oracle.tools.Options;

import com.oracle.tools.runtime.Application;
import com.oracle.tools.runtime.Platform;
import com.oracle.tools.runtime.Profile;

import com.oracle.tools.runtime.coherence.CoherenceClusterMember;

import com.oracle.tools.runtime.java.options.SystemProperties;
import com.oracle.tools.runtime.java.options.SystemProperty;

/**
 * An {@link Option} to specify the Operational Override of a {@link CoherenceClusterMember}.
 * <p>
 * Copyright (c) 2016. All Rights Reserved. Oracle Corporation.<br>
 * Oracle is a registered trademark of Oracle Corporation and/or its affiliates.
 *
 * @author Brian Oliver
 */
public class OperationalOverride implements Profile, Option
{
    /**
     * The tangosol.coherence.override property.
     */
    public static final String PROPERTY = "tangosol.coherence.override";

    /**
     * The operational override uri.
     */
    private String uri;


    /**
     * Constructs a {@link OperationalOverride} for the specified uri.
     *
     * @param uri  the uri
     */
    private OperationalOverride(String uri)
    {
        if (uri == null)
        {
            throw new NullPointerException("OperationalOverride must not be null");
        }
        else
        {
            this.uri = uri;
        }
    }


    /**
     * Obtains the uri of the {@link OperationalOverride}.
     *
     * @return  the uri of the {@link OperationalOverride}
     */
    public String getUri()
    {
        return uri;
    }


    /**
     * Obtains a {@link OperationalOverride} for a specified uri.
     *
     * @param uri  the uri of the {@link OperationalOverride}
     *
     * @return a {@link OperationalOverride} for the specified uri
     */
    public static OperationalOverride of(String uri)
    {
        return new OperationalOverride(uri);
    }


    @Override
    public void onBeforeLaunch(Platform platform,
                               Options  options)
    {
        SystemProperties systemProperties = options.get(SystemProperties.class);

        if (systemProperties != null)
        {
            options.add(SystemProperty.of(PROPERTY, uri));
        }
    }


    @Override
    public void onAfterLaunch(Platform    platform,
                              Application application,
                              Options     options)
    {
    }


    @Override
    public void onBeforeClose(Platform    platform,
                              Application application,
                              Options     options)
    {
    }


    @Override
    public boolean equals(Object o)
    {
        if (this == o)
        {
            return true;
        }

        if (!(o instanceof OperationalOverride))
        {
            return false;
        }

        OperationalOverride executable = (OperationalOverride) o;

        return uri.equals(executable.uri);

    }


    @Override
    public int hashCode()
    {
        return uri.hashCode();
    }
}
