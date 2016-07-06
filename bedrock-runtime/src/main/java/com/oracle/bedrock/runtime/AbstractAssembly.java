/*
 * File: AbstractAssembly.java
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

package com.oracle.bedrock.runtime;

import com.oracle.bedrock.Option;
import com.oracle.bedrock.OptionsByType;
import com.oracle.bedrock.annotations.Internal;
import com.oracle.bedrock.deferred.DeferredPredicate;
import com.oracle.bedrock.runtime.options.Discriminator;
import com.oracle.bedrock.runtime.options.StabilityPredicate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.oracle.bedrock.deferred.DeferredHelper.ensure;
import static com.oracle.bedrock.deferred.DeferredHelper.eventually;
import static com.oracle.bedrock.predicate.Predicates.is;

/**
 * A base implementation of an {@link Assembly}.
 * <p>
 * Copyright (c) 2014. All Rights Reserved. Oracle Corporation.<br>
 * Oracle is a registered trademark of Oracle Corporation and/or its affiliates.
 *
 * @author Brian Oliver
 *
 * @param <A>  the type of {@link Application} that belongs to the {@link Assembly}.
 */
@Internal
public abstract class AbstractAssembly<A extends Application> implements Assembly<A>,
                                                                         ApplicationListener<A>,
                                                                         ApplicationStream<A>
{
    /**
     * The {@link Application}s that belong to the {@link Assembly}.
     */
    protected CopyOnWriteArrayList<A> applications;

    /**
     * Is the {@link Assembly} closed?
     */
    protected AtomicBoolean isClosed;

    /**
     * The number of {@link Application}s that have been added to the {@link Assembly}
     * over time.  This is used for adding new {@link Discriminator}s when expanding
     * an {@link Assembly}.
     */
    protected AtomicInteger applicationCount;

    /**
     * The {@link OptionsByType} used for constructing the {@link Assembly}.
     */
    protected OptionsByType optionsByType;


    /**
     * Constructs an {@link AbstractAssembly} given a list of {@link Application}s.
     *
     * @param applications   the {@link Application}s in the {@link Assembly}.
     * @param optionsByType  the {@link OptionsByType} used to launch the {@link Application}s
     */
    public AbstractAssembly(List<? extends A> applications,
                            OptionsByType     optionsByType)
    {
        this.applications     = new CopyOnWriteArrayList<>();
        this.isClosed         = new AtomicBoolean(false);
        this.applicationCount = new AtomicInteger(applications.size());
        this.optionsByType    = optionsByType;

        // add the applications to the assembly
        for (A application : applications)
        {
            // add this assembly as a feature of each application
            // (so that the application can notify the assembly of independent lifecycle events)
            application.add(Assembly.class, this);

            // add the application to the assembly
            this.applications.add(application);
        }

        // notify the assembly that it has expanded (for the first time)
        onExpanded(applications);
    }


    /**
     * Determine if the {@link Assembly} is closed.
     *
     * @return  <code>true</code> if the {@link Assembly} is closed
     */
    public boolean isClosed()
    {
        return isClosed.get();
    }


    /**
     * Obtains an {@link Application} in this {@link Assembly} matching the given name
     * (as a regular expression).
     *
     * If no such {@link Application} exists in the {@link Assembly}, <code>null</code>
     * will be returned.  If multiple {@link Application}s in the {@link Assembly}
     * matching the given name, an arbitrary {@link Application} matching the specified
     * name will be returned
     *
     * @param pattern  a regular expression for the name of the application
     *
     * @return the application in this {@link Assembly} matching the given name or null
     *         if no application has matched with the given name
     */
    public A get(String pattern)
    {
        // when not found, search for the application using the pattern
        for (A application : applications)
        {
            if (application.getName().matches(pattern))
            {
                return application;
            }
        }

        return null;
    }


    /**
     * Obtains the {@link Application}s in this {@link Assembly} where by their
     * {@link Application#getName()} matches the specified pattern (as a regex)
     * or starts with the specified pattern.
     *
     * @param pattern  the pattern to match with {@link Application#getName()}s
     * @return an {@link Iterable} over the {@link Application}s matching or
     *         starting with the specified pattern
     */
    public Iterable<A> getAll(String pattern)
    {
        LinkedList<A> list = new LinkedList<>();

        for (A application : applications)
        {
            String name = application.getName();

            if (name.startsWith(pattern) || name.matches(pattern))
            {
                list.add(application);
            }
        }

        return list;
    }


    /**
     * Obtains the {@link Application}s in this {@link Assembly}
     * satifying the specified {@link Predicate}.
     *
     * @param predicate  the {@link Predicate} to test {@link Application}s
     * @return an {@link Iterable} over the {@link Application}s satisfying the
     *         {@link Predicate}
     */
    public Iterable<A> getAll(Predicate<? super A> predicate)
    {
        LinkedList<A> list = new LinkedList<>();

        for (A application : applications)
        {
            if (predicate.test(application))
            {
                list.add(application);
            }
        }

        return list;
    }


    /**
     * Adds the specified {@link Application} to the {@link Assembly}.
     *
     * @param application  the {@link Application} to add
     *
     * @throws IllegalStateException  when the {@link Assembly} {@link #isClosed}
     */
    public void add(A application)
    {
        if (isClosed())
        {
            throw new IllegalStateException("Can't add [" + application + "] as the " + this.getClass().getName()
                                            + " is closed");
        }
        else if (get(application.getName()) != null)
        {
            // do nothing when an application of the same name is already in the assembly
            // (with the same name)
        }
        else
        {
            // ensure the assembly is a feature of the application so that it can be called back for lifecycle events
            application.add(Assembly.class, this);

            // add the application to the assembly
            applications.add(application);

            // notify the assembly implementation that it has expanded
            onExpanded(Collections.singletonList(application));
        }

    }


    /**
     * Removes the specified {@link Application} to the {@link Assembly}.
     *
     * @param application  the {@link Application} to remove
     *
     * @return <code>true</code> if the specified {@link Application} was removed
     *         <code>false</code> otherwise
     *
     * @throws IllegalStateException  when the {@link Assembly} {@link #isClosed}
     */
    public boolean remove(A application)
    {
        if (isClosed())
        {
            throw new IllegalStateException("Can't remove [" + application + "] as the " + this.getClass().getName()
                                            + " is closed");
        }
        else
        {
            // ensure the assembly is no longer a feature so that won't be called back for lifecycle events
            application.remove(Assembly.class);

            // remove the application from the assembly
            return applications.remove(application);
        }
    }


    /**
     * Expands the number of {@link Application}s in the {@link Assembly} by launching and adding the specified number
     * of {@link Application}s on the provided {@link Platform} using the zero or more provided {@link Option}s.
     *
     * @param count             the number of instances of the {@link Application} that should be launched and added
     *                          to the {@link Assembly}
     * @param platform          the {@link Platform} on which to launch the {@link Application}s
     * @param applicationClass  the class of {@link Application}
     * @param options           the {@link Option}s to use for launching the {@link Application}s
     *
     * @see Platform#launch(String, Option...)
     * @see AssemblyBuilder#include(int, Class, Option...)
     */
    public void expand(int                count,
                       Platform           platform,
                       Class<? extends A> applicationClass,
                       Option...          options)
    {
        // we keep track of the new applications that are launched
        ArrayList<A> launchedApplications = new ArrayList<>();

        // determine the base expanding options
        OptionsByType expandingOptions = OptionsByType.of(options);

        for (int i = 0; i < count; i++)
        {
            OptionsByType launchOptions = OptionsByType.of(expandingOptions);

            // create a discriminator for the new application
            launchOptions.add(Discriminator.of(applicationCount.incrementAndGet()));

            // launch the application
            A application = platform.launch(applicationClass, launchOptions.asArray());

            // remember the application
            launchedApplications.add(application);

            // add the application to the assembly
            add(application);
        }

        // notify the assembly implementation that it has expanded
        onExpanded(launchedApplications);
    }


    /**
     * Called when the {@link Assembly} is first created and after the
     * {@link #expand(int, Platform, Class, Option...)} and {@link #add(Application)} methods
     * have increased the number of {@link Application}s in the {@link Assembly}.
     *
     * @param applications  the newly added {@link Application}s to the {@link Assembly}
     */
    protected void onExpanded(List<? extends A> applications)
    {
        // by default there's nothing to do after expanding an assembly
    }


    /**
     * Called prior to the {@link Assembly} closing and relaunching the specified {@link Application}
     * using the provided {@link OptionsByType}.  When called the {@link Application} has yet
     * to be closed.
     *
     * @param application    the {@link Application} to be relaunched
     * @param optionsByType  the {@link OptionsByType} provided for relaunching
     */
    protected void onRelaunching(A             application,
                                 OptionsByType optionsByType)
    {
        // by default there's nothing to do prior to relaunching an application
    }


    /**
     * Called prior to the {@link Assembly} relaunching the now closed {@link Application} on the
     * specified {@link Platform}, allowing an implementation to override and customize the provided
     * {@link OptionsByType}, arguments and properties.
     *
     * @param platform       the {@link Platform}
     * @param optionsByType  the {@link OptionsByType}
     */
    protected void onRelaunching(Platform      platform,
                                 OptionsByType optionsByType)
    {
        // by default there's nothing to do prior to relaunching an application
    }


    /**
     * Called after the {@link Assembly} has relaunched the {@link Application} using the
     * provided {@link OptionsByType}.
     *
     * @param original       the original (now closed) {@link Application}
     * @param restarted      the restarted {@link Application}
     * @param optionsByType  the launch {@link OptionsByType}
     */
    protected void onRelaunched(A             original,
                                A             restarted,
                                OptionsByType optionsByType)
    {
        // by default there's nothing to do after relaunching an application
    }


    /**
     * Restarts the specified {@link Application}s using the provide {@link Option}s.
     *
     * @param applications  the {@link Application}s to relaunch
     * @param options       the {@link Option}s for restarting
     */
    protected void relaunch(List<? extends A> applications,
                            Option...         options)
    {
        // close and relaunch each application one at a time
        applications.forEach(
            application -> {

            // only relaunch if the application is in the assembly
                if (remove(application))
                {
                    // obtain some information about the application before closing it
                    Platform      platform           = application.getPlatform();
                    OptionsByType applicationOptions = application.getOptions();

                    // establish the launch options
                    // (based on the application and specified options)
                    OptionsByType launchOptions = OptionsByType.of(applicationOptions).addAll(options);

                    // notify the assembly that the application is about to be relaunched
                    onRelaunching(application, launchOptions);

                    // close the application (using the options)
                    application.close(options);

                    // ensure the stability of the assembly (if required)
                    StabilityPredicate<Assembly> stabilityPredicate =
                        launchOptions.getOrDefault(StabilityPredicate.class,
                                                   null);

                    if (stabilityPredicate != null)
                    {
                        DeferredPredicate deferredPredicate = new DeferredPredicate<>(this, stabilityPredicate.get());

                        ensure(eventually(deferredPredicate), is(true));
                    }

                    // we use a new discriminator
                    launchOptions.add(Discriminator.of(applicationCount.incrementAndGet()));

                    // notify the assembly we're about to relaunch an application
                    onRelaunching(platform, launchOptions);

                    // we'll create the same class of application
                    Class<A> applicationClass = (Class<A>) application.getClass();

                    // (re) launch the application
                    A relaunchedApplication = platform.launch(applicationClass, applicationOptions.asArray());

                    // notify the assembly that the application was restarted
                    onRelaunched(application, relaunchedApplication, launchOptions);

                    // add the application to the assembly
                    add(relaunchedApplication);
                }
            });

        // finally ensure the stability of the assembly (if required)
        OptionsByType                optionsByType      = OptionsByType.of(options);

        StabilityPredicate<Assembly> stabilityPredicate = optionsByType.getOrDefault(StabilityPredicate.class, null);

        if (stabilityPredicate != null)
        {
            DeferredPredicate deferredPredicate = new DeferredPredicate<>(this, stabilityPredicate.get());

            ensure(eventually(deferredPredicate), is(true));
        }
    }


    @Override
    public Iterator<A> iterator()
    {
        return applications.iterator();
    }


    @Override
    public void close()
    {
        this.close(new Option[]
        {
        });
    }


    @Override
    public void close(Option... options)
    {
        if (isClosed.compareAndSet(false, true))
        {
            for (A application : applications)
            {
                if (application != null)
                {
                    // ensure the assembly is no longer a feature so that won't be called back for lifecycle events
                    application.remove(Assembly.class);

                    try
                    {
                        application.close(options);
                    }
                    catch (Exception e)
                    {
                        // skip: we always ignore
                    }
                }
            }

            // now remove the applications
            applications.clear();
        }
    }


    @Override
    public void onClosing(A             application,
                          OptionsByType optionsByType)
    {
        if (!isClosed())
        {
            // when we're not in the process of closing the cluster we must remove
            // the application as it's been closed explicitly
            applications.remove(application);
        }
    }


    @Override
    public void onClosed(A             application,
                         OptionsByType optionsByType)
    {
        // SKIP: nothing to do when an application is closed
    }


    @Override
    public void onLaunched(A application)
    {
        // SKIP: nothing to do when an application is launched
    }


    /**
     * Obtains an {@link ApplicationStream} for the current {@link Application}s
     * in the {@link Assembly}.
     *
     * @return  an {@link ApplicationStream}
     */
    public ApplicationStream<A> stream()
    {
        return new StreamAdapter(applications.stream());
    }


    @Override
    public void forEach(Consumer<? super A> action)
    {
        stream().forEach(action);
    }


    @Override
    public long count()
    {
        return applications.size();
    }


    @Override
    public ApplicationStream<A> filter(java.util.function.Predicate<? super A> predicate)
    {
        return stream().filter(predicate);
    }


    @Override
    public boolean allMatch(java.util.function.Predicate<? super A> predicate)
    {
        return stream().allMatch(predicate);
    }


    @Override
    public boolean anyMatch(java.util.function.Predicate<? super A> predicate)
    {
        return stream().anyMatch(predicate);
    }


    @Override
    public boolean noneMatch(java.util.function.Predicate<? super A> predicate)
    {
        return stream().noneMatch(predicate);
    }


    @Override
    public ApplicationStream<A> limit(int maximum)
    {
        return stream().limit(maximum);
    }


    @Override
    public ApplicationStream<A> peek(Consumer<? super A> consumer)
    {
        return stream().peek(consumer);
    }


    @Override
    public <R, T> R collect(Collector<? super A, T, R> collector)
    {
        return stream().collect(collector);
    }


    @Override
    public <R> R collect(Supplier<R>              supplier,
                         BiConsumer<R, ? super A> accumulator,
                         BiConsumer<R, R>         combiner)
    {
        return stream().collect(supplier, accumulator, combiner);
    }


    @Override
    public Optional<A> findAny()
    {
        return stream().findAny();
    }


    @Override
    public Optional<A> findFirst()
    {
        return stream().findFirst();
    }


    @Override
    public ApplicationStream<A> unordered()
    {
        return stream().unordered();
    }


    @Override
    public void relaunch(Option... options)
    {
        stream().relaunch(options);
    }


    /**
     * An internal implementation of an {@link ApplicationStream} that
     * adapts a regular {@link Stream} of {@link Application}s into an
     * {@link ApplicationStream}.  This allows the {@link ApplicationStream}
     * to interact with the underlying {@link Assembly} from which it was
     * produced.
     */
    private class StreamAdapter implements ApplicationStream<A>
    {
        /**
         * The {@link Stream} of {@link Application}s to adapt into an {@link ApplicationStream}.
         */
        private Stream<A> stream;


        /**
         * Constructs a {@link StreamAdapter}
         * @param stream
         */
        StreamAdapter(Stream<A> stream)
        {
            this.stream = stream;
        }


        @Override
        public void close(Option... options)
        {
            stream.forEach(a -> a.close(options));
        }


        @Override
        public void forEach(Consumer<? super A> consumer)
        {
            stream.forEach(consumer);
        }


        @Override
        public long count()
        {
            return stream.count();
        }


        @Override
        public ApplicationStream<A> filter(Predicate<? super A> predicate)
        {
            return new StreamAdapter(stream.filter(predicate));
        }


        @Override
        public boolean allMatch(Predicate<? super A> predicate)
        {
            return stream.allMatch(predicate);
        }


        @Override
        public boolean anyMatch(Predicate<? super A> predicate)
        {
            return stream.anyMatch(predicate);
        }


        @Override
        public boolean noneMatch(Predicate<? super A> predicate)
        {
            return stream.noneMatch(predicate);
        }


        @Override
        public ApplicationStream<A> limit(int maximum)
        {
            return new StreamAdapter(stream.limit(maximum));
        }


        @Override
        public ApplicationStream<A> peek(Consumer<? super A> consumer)
        {
            return new StreamAdapter(stream.peek(consumer));
        }


        @Override
        public <R, T> R collect(Collector<? super A, T, R> collector)
        {
            return stream.collect(collector);
        }


        @Override
        public <R> R collect(Supplier<R>              supplier,
                             BiConsumer<R, ? super A> accumulator,
                             BiConsumer<R, R>         combiner)
        {
            return stream.collect(supplier, accumulator, combiner);
        }


        @Override
        public Optional<A> findAny()
        {
            return stream.findAny();
        }


        @Override
        public Optional<A> findFirst()
        {
            return stream.findFirst();
        }


        @Override
        public ApplicationStream<A> unordered()
        {
            // collect all of the applications in this stream
            List<A> applications = stream.collect(Collectors.toList());

            // shuffle them!
            Collections.shuffle(applications);

            // return a new stream based on the shuffled applications
            return new StreamAdapter(applications.stream());
        }


        @Override
        public void relaunch(Option... options)
        {
            // collect the applications we need to relaunch
            List<A> applications = stream.collect(Collectors.toList());

            // have the assembly relaunch these applications with the provided options
            AbstractAssembly.this.relaunch(applications, options);
        }
    }
}
