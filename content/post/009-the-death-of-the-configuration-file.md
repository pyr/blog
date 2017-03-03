#+title: The death of the configuration file
#+date: 2011-09-18

Taking on a new platform design recently I thought it was interesting to
see how things evolved in the past years and how we design and think
about platform architecture.

### So what do we do ?

As system developers, system administrators and system engineers, what
do we do ?

-   We develop software
-   We design architectures
-   We configure systems

But it isn't the purpose of our jobs, for most of us, our purpose is to
generate business value. From a non technical perspective we generate
business value by creating a system which renders one or many functions
and provides insight into its operation.

And we do this by developing, logging, configuration and maintaining
software across many machines.

When I started doing this - back when knowing how to write a sendmail
configuration file could get you a paycheck - it all came down to
setting up a few machines, a database server a web server a mail server,
each logging locally and providing its own way of reporting metrics.

When designing custom software, you would provide reports over a local
**AF_UNIX** socket, and configure your software by writing elegant
parsers with **yacc** (or its GNU equivalent, **bison**).

When I joined the **OpenBSD** team, I did a lot of work on configuration
files, ask any members of the team, the configuration files are a big
concern, and careful attention is put into clean, human readable and
writable syntax, additionally, all configuration files are expected to
look and feel the same, for consistency.

It seems as though the current state of large applications now demands
another way to interact with operating systems, and some tools are now
leading the way.

### So what has changed ?

While our mission is still the same from a non technical perspective,
the technical landscape has evolved and went through several phases.

1.  The first era of repeatable architecture

    We first realized that as soon as several machines performed the
    same task the need for repeatable, coherent environments became
    essential. Typical environments used a combination of **cfengine**,
    **NFS** and mostly **perl** scripts to achieve these goals.

    Insight and reporting was then providing either by horrible
    proprietary kludges that I shall not name here, or emergent tools
    such as **netsaint** (now **nagios**), **mrtg** and the like.

2.  The XML mistake

    Around that time, we started hearing more and more about **XML**,
    then touted as the solution to almost every problem. The rationale
    was that **XML** was - **somewhat** - easy to parse, and would allow
    developers to develop configuration interfaces separately from the
    core functionality.

    While this was a noble goal, it was mostly a huge failure. Above
    all, it was a victory of developers over people using their
    software, since they didn't bother writing syntax parsers and let
    users cope with the complicated syntax.

    Another example was the difference between Linux's **iptables** and
    OpenBSD's **pf**. While the former was supposed to be the backend
    for a firewall handling tool that never saw the light of day, the
    latter provided a clean syntax.

3.  Infrastructure as code

    Fast forward a couple of years, most users of **cfengine** were fed
    up with its limitations, architectures while following the same
    logic as before became bigger and bigger. The need for repeatable
    and sane environments was as important as it ever was.

    At that point of time, **PXE** installations were added to the mix
    of big infrastructures and many people started looking at **puppet**
    as a viable alternative to **cfengine**.

    **puppet** provided a cleaner environment, and allowed easier
    formalization of technology, platform and configuration.
    Philosophically though, **puppet** stays very close to **cfengine**
    by providing a way to configure large amounts of system through a
    central repository.

    At that point, large architectures also needed command and control
    interfaces. As noted before, most of these were implemented as
    **perl** or shell scripts in **SSH** loops.

    On the monitoring and graphing front, not much was happening,
    **nagios** and **cacti** were almost ubiquitous, while some tools
    such as **ganglia** and **collectd** were making a bit of progress.

### Where are we now ?

At some point recently, our applications started doing more. While for a
long time the canonical dynamic web application was a busy forum, more
complex sites started appearing everywhere. We were not building and
operating sites anymore but applications. And while with the help of
**haproxy**, **varnish** and the likes, the frontend was mostly a
settled affair, complex backends demanded more work.

At the same time the advent of social enabled applications demanded much
more insight into the habits of users in applications and thorough
analytics.

New tools emerged to help us along the way:

-   In memory key value caches such as **memcached** and **redis**
-   Fast elastic key value stores such as **cassandra**
-   Distributed computing frameworks such as **hadoop**
-   And of course on demand virtualized instances, aka: **The Cloud**

1.  Some daemons only provide small functionality

    The main difference in the new stack found in backend systems is
    that the software stacks that run are not useful on their own
    anymore.

    Software such as **zookeeper**, **kafka**, **rabbitmq** serve no
    other purpose that to provide supporting services in applications
    and their functionality are almost only available as libraries to be
    used in distributed application code.

2.  Infrastructure as code is not infrastructure in code !

    What we missed along the way it seems is that even though our
    applications now span multiple machines and daemons provide a subset
    of functionality, most tools still reason with the machine as the
    top level abstraction.

    **puppet** for instance is meant to configure nodes, not cluster and
    makes dependencies very hard to manage. A perfect example is the
    complications involved in setting up configurations dependent on
    other machines.

    Monitoring and graphing, except for **ganglia** has long suffered
    from the same problem.

### The new tools we need

We need to kill local configurations, plain and simple. With a simple
enough library to interact with distant nodes, starting and stopping
service, configuration can happen in a single place and instead of
relying on a repository based configuration manager, configuration
should happen from inside applications and not be an external process.

If this happens in a library, command & control must also be added to
the mix, with centralized and tagged logging, reporting and metrics.

This is going to take some time, because it is a huge shift in the way
we write software and design applications. Today, configuration
management is a very complex stack of workarounds for non standardized
interactions with local package management, service control and software
configuration.

Today dynamically configuring **bind**, **haproxy** and **nginx**,
installing a package on a **Debian** or **OpenBSD**, restarting a
service, all these very simple tasks which we automate and operate from
a central repository force us to build complex abstractions. When using
**puppet**, **chef** or **pallet**, we write complex templates because
software was meant to be configured by humans.

The same goes for checking the output of running arbitrary scripts on
machines.

1.  Where we'll be tomorrow

    With the ease PaaS solutions bring to developers, and offers such as
    the ones from VMWare and open initiatives such as OpenStack, it
    seems as though virtualized environments will very soon be found
    everywhere, even in private companies which will deploy such
    environments on their own hardware.

    I would not bet on it happening but a terse input and output format
    for system tools and daemons would go a long way in ensuring easy
    and fast interaction with configuration management and command and
    control software.

    While it was a mistake to try to push **XML** as a terse format
    replacing configuration file to interact with single machines, a
    terse format is needed to interact with many machines providing the
    same service, or to run many tasks in parallel - even though,
    admittedly , tools such as **capistrano** or **mcollective** do a
    good job at running things and providing sensible output.

2.  The future is now !

    Some projects are leading the way in this new orientation, 2011 as
    I've seen it called will be the year of the time series boom. For
    package management and logging, Jordan Sissel released such great
    tools as **logstash** and **fpm**. For easy graphing and deployment
    **etsy** released great tools, amongst which **statsd**.

    As for bridging the gap between provisionning, configuration
    management, command and control and deploys I think two tools, both
    based on jclouds[^1] are going in the right direction:

    -   Whirr[^2]: Which let you start a cluster through code, providing

    recipes for standard deploys (**zookeeper**, **hadoop**)

    -   pallet[^3]: Which lets you describe your infrastructure as code
        and

    interact with it in your own code. **pallet**'s phase approach to
    cluster configuration provides a smooth dependency framework which
    allows easy description of dependencies between configuration across
    different clusters of machines.

3.  Who's getting left out ?

    One area where things seem to move much slower is network device
    configuration, for people running open source based load-balancers
    and firewalls, things are looking a bit nicer, but the switch
    landscape is a mess. As tools mostly geared towards public cloud
    services will make their way in private corporate environments,
    hopefully they'll also get some of the programmable

[^1]: <http://www.jclouds.org>

[^2]: <http://whirr.apache.org>

[^3]: <https://github.com/pallet/pallet>
