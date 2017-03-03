#+title: Some more thoughts on monitoring
#+date: 2011-07-08

Lately, monitoring has been a trending topic from the devops crowd.
[ripienaar](http://www.devco.net/archives/2011/03/19/thinking_about_monitoring_frameworks.php)
 and [Jason Dixon](http://obfuscurity.com/2011/07/Monitoring-Sucks-Do-Something-About-It) amongst other have voiced what
many are thinking. They've done a good job describing what's wrong and
what sort of tool the industry needs. They also express clearly the
need to part from a monolithic supervision solution and monolithic
graphing solution.

I'll take my shot at expressing what I feel is wrong in the current tools:

## Why won't you cover 90% of use cases?

Supervision is hard, each production is different, and complex
business logic must be tested, so indeed, a monitoring tool must be
able to be extended easily, that's a given and every supervision tool
got this right. But why on earth should tests that every production
will need be implemented as extensions ? Let's take a look at the
expected value which is the less intrusive way to check for a
machine's load average:

- The nagios core engine determines that an snmp check must be run for a machine
- Fork, execute a shell which execs the check_snmp command
- Feed the right arguments to snmpget

You think I am kidding ? I am not. Of course each machine needing a
check will need to go through this steps. So for as few as 20 machines
requiring supervision  at each check interval 60 processes would be
spawned. 60 processes spawned for what ? Sending 20 udp packets,
waiting for a packet in return. Same goes for TCP, ICMP, and many
more.

But it gets better ! Want to check more than one SNMP OIDs on the same
machine ? The same process happens for every OID, which means that if
you have

Now consider the common use case, what does a supervision and graphing
engine do most of its time:

- Poll ICMP
- Poll TCP - sometimes sending or expecting a payload, say for HTTP or
SMTP checks
- Poll SNMP

So for a simple setup of 20 machines, checking these simple services,
you could be well into the thousands of process spawning *every* check
interval *per machine*. If you have a reasonable interval, say 30
seconds or a minute.

Add to that some custom hand written scripts in perl, python, ruby -
or worse, bash - to check for business logic and you end up having to
sacrifice a large machine (or cloud instance) for simple checks.

That would be my number one requirement for a clean monitoring system:
Cover the simple use cases ! Better yet, do it asynchronously !
Because for the common use case, all monitoring needs to do is wait on
I/O. Every language has nice interfaces for handling evented I/O the
core of a poller should be evented.

There are of course a few edge cases which make it hard to use that
technique, ICMP coming to mind since it requires root access on UNIX
boxes, but either privilege separation or a root process for ICMP
checks can mitigate that difficulty.

## Why is alerting supposed to be different than graphing ?

Except from some less than ideal solutions &ndash; looking at you
[Zabbix](http://zabbix.com) - Supervision and Graphing are most of the time two
separate tool suites, which means that in many cases, the same metrics
are polled several times. The typical web shop now has a cacti and
nagios installation, standard metrics such as available disk space
will be polled by cacti and then by nagios (in many cases through an
horrible private mechanism such as nrpe).

Functionally speaking the tasks to be completed are rather simple:

- Polling a list of data-points
- Ability to create compound data-points based on polled values
- Alerting on data-point thresholds or conditions
-- Storing time-series of data-points

These four tasks are all that is needed for a complete monitoring and
graphing solution. Of course this is only the core of the solution and
other features are needed, but as far as data is concerned these four
tasks are sufficient.

## How many times will we have to reinvent SNMP

I'll give you that, SNMP sucks, the S in the name - simple - is a
blatant lie. In fact, for people running in the cloud, a collector
such as [Collectd](http://collectd.org) might be a better option. But the fact that
every monitoring application "vendor" has a private non
inter-operable collecting agent is distressing to say the least.

SNMP can rarely be totally avoided and when possible should be relied
upon. Well thought out, easily extensible collectors are nice
additions but most solutions are clearly inferior to SNMP and added
stress on machines through sequential, process spawning solutions.

## A broken thermometer does not mean your healthy

(LLDP, CDP, SNMP) are very useful to make sure assumptions you make on
a production environment match the reality, they should never be the
basis of decisions or considered exhaustive.

A simple analogy, using discovery based monitoring solutions is
equivalent to saying you store your machine list in a DNS zone file.
It should be true, there should be mechanisms to ensure it is true,
but might get out of sync over time: it cannot be treated as a source
of truth.

## Does everyone need a horizontally scalable solution ?

I appreciate the fact that every one wants the next big tool to be
horizontally scalable, to distribute checks geographically. The thing
is, most people need this because a single machine or instance&rsquo;s
limits are very easily reached with today&rsquo;s solutions. A single
process evented check engine, with an embedded interpretor allowing
simple business logic checks should be small enough to allow matching
most peoples needs.

This is not to say, once the single machine limit is reached, a
distributed mode should not be available for larger installations. But
the current trend seems to recommend using AMQP type transports (e.g:
which while still being more economic than nagios' approach will put
an unnecessary strain on singe machine setups and also raise the bar
of prerequisites for a working installation.

Now as far as storage is concerned, there are enough options out there
to choose from which make it easy to scale storage. Time-series and
data-points are perfect candidates for non relational databases and
should be leveraged in this case. For single machine setups, RRD type
databases should also be usable.

## Keep it decoupled

The above points can be addressed by using decoupled software. Cacti
for instance is a great visualization interface but has a strongly
coupled poller and storage engine, making it very cumbersome to change
parts of its functionality (for instance replacing the RRD storage
part).

Even though I believe in making it easy to use single machine setups,
each part should be easily exported elsewhere or replaced. Production
setups are complex and demanding, each having their specific
prerequisites and preferences.

Some essential parts stand out as easily decoupled:

- Data-point pollers
- Data-point storage engine
- Visualization Interface
- Alerting

## Current options

There are plenty of tools which even though they need a lot of work to
be made to work together still provide a "good enough" feeling,
amongst those I have been happy to work with:

- **Nagios**: The lesser of many evils
- **Collectd**: Nice poller which can be used from nagios for alerting
- **Graphite** http://graphite.wikidot.com: Nice grapher which is inter-operable with collectd
- **OpenTSDB** http://opentsdb.net: Seems like a step in the right direction but requires a complex stack to be setup.

## Final Words

Now of course if all that time spent writing articles was spent
coding, we might get closer to a good solution. I will do my best to
unslack(); and get busy coding.

