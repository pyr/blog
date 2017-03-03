#+title: Easy clojure logging set-up with logconfig
#+date: 2014-10-15

\*TL;DR\*: I love
[clojure.tools.logging](https://github.com/clojure/tools.logging), but
setting JVM logging up can be a bit frustrating, I wrote
[logconfig](https://github.com/pyr/logconfig) to help.

When I started clojure development (about 5 years ago now), I was new to
the JVM - having no real Java background. My first clojure projects
where long running, data consuming tasks and thus logging was a
consideration from the start. The least I could say is that navigating
the available logging options and understanding how to configure each
framework was daunting.

### JVM logging 101

Once you get around to understanding how logging works on the JVM, it
makes a log of sense, for those not familiar with the concepts, here is
a quick recap - I will be explaining this in the context of
[log4j](http://logging.apache.org/log4j/1.2/), but the same holds for
[slf4j](http://www.slf4j.org), [logback](http://logback.qos.ch) and
other frameworks:

-   Logging frameworks can be configured inside or outside the
    application.
-   The common method is for logging to be configured outside, with a
    specific configuration file.
-   User-provided classes can be added to the JVM to format (through
    layout) or write (through appenders) logs in a different manner.

This proves really useful, since you might need to ship logs as
JSON-formatted payloads to integrate with your
[logstash](http://logstash.org) infrastructure for instance, you might
even rely on sending logs over the network, without the original
application writer having had to worry about these use-cases.

### The meat of the problem

While having the possibility of configuring logging in such a way, it's
not a use case many people have, and spreading an application's
configuration through-out several files does not facilitate starting
out.

I think [elasticsearch](http://elasticsearch.org) is a project which
gets things right, allowing logging to be configured from the same file
than the rest of the service, only exposing the most common options.

### Introducing logconfig

[logconfig](https://github.com/pyr/logconfig), which is available on
clojars (at version **0.7.1** at the time of writing), provides you with
a simple way of taking care of that problem, it does the following
things:

-   Provide a way to configure log4j from a clojure map.
-   Allow overriding of the configuration for people wanting to provide
    their own log4j.properties config.
-   Support both enhanced patterns and JSON event as layouts, enabling
    easy integration with logstash.
-   Append files with a time based rolling policy
-   Optional console output (for people using runit or debug purposes).

A nice side-effect of relying on logconfig is the reduced coordinates
matrix:

``` clojure
;; before
  :dependencies [...
                 [commons-logging/commons-logging "1.2"]
                 [org.slf4j/slf4j-log4j12 "1.7.7"]
                 [net.logstash.log4j/jsonevent-layout "1.7"]
                 [log4j/apache-log4j-extras "1.2.17"]
                 [log4j/log4j "1.2.17"
                   :exclusions [javax.mail/mail
                                javax.jms/jms
                                com.sun.jdmk/jmxtools
                                com.sun.jmx/jmxri]]]
;; after
  :dependencies [...
                 [org.spootnik/logconfig "0.7.1"]]
```

### Sample use-case: fleet

[fleet,](https://github.com/pyr/fleet) our command and control framework
at [exoscale](https://exoscale.ch) is configured through a YAML file,
the file is read and contains several sections: `transport`, `codec`,
`scenarios`, `http`, `security` and `logging`.

``` yaml
logging:
  console: true
  files:
    - "/var/log/fleet.log"
security:
  ca-priv: "doc/ca/ca.key"
  certdir: "doc/ca"
  suffix: "pem"
scenarios:
  path: "doc/scenarios"
http:
  port: 8080
  origins:
    - "http://example.com"
```

The `logging` key in the YAML file is expected to adhere to logconfig's
format and will be fed to logconfig. Users relying on existing
log4j.properties configuration can also set `external` to true in the
YAML config and provide their log4j configuration through the standard
JVM properties.

Both [cyanite](https://github.com/pyr/cyanite) and
[pithos](https://github.com/exoscale/pithos) now also rely on this
mechanism.

I hope this can be useful to other developers building services, apps
and daemons in clojure, the full documentation for the API is available
here: <http://pyr.github.io/logconfig>, check-out the project at
<https://github.com/pyr/logconfig>.
