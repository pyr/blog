#+title: Another year of Clojure
#+date: 2012-07-04

### Clojure at paper.li

I've been involved with clojure almost exclusively for a year as
smallriver's lead architect, working on the paper.li product and wanted
to share my experience of clojure in the real world.

I had a previous experience with clojure where I put it to work where
ruby on rails wasn't a natural fit, and although smallrivers is a close
neighbor of typesafe in Switzerland, my previous experience with the
language made it prevail on scala.

### Why clojure ?

While working on the backend architecture at a previous company I
decided to evaluate three languages which met the needs I was faced
with:

-   erlang
-   scala
-   clojure

I decided to tackle the same simple task in all three languages and see
how each would fare and how I felt about them. The company's language at
that time was Ruby and JS, and coming from a C background, I wanted a
language which provided simplicity, good data structure support and
concurrency features, while allowing us to still code quickly.

While naturally drawn to Erlang, I quickly had to set it apart because
the stack that was starting to emerge at the time had JVM based parts
and would benefit greatly from a language targetting the JVM. I was a
bit bummed because some tools in the erlang world were very exciting and
the lightweight actors were interesting for a part of our stack.

Scala made a very strong first impression on me, but in practice I was
taken aback by some aspects of it: the lack of coherency of open source
projects found on the net in terms of style, which made it hard to see
which best practices and guidelines would have to be taught to the team,
some of the code I found was almost reminiscent of perl a few year back,
in the potential it had to become unmaintainable some time later. The
standard build tool - SBT - also made a very weak impression. It seemed
to be a clear step back from maven which given the fact that maven isn't
a first class citizen in the scala world seemed worrying.

Clojure took the cake, in part because it clicked with the lisper in me,
in part because the common idioms that emerged from the code I read bore
a lot of similarity with the way we approached ruby. The dynamic typing
promised succinct code and the notation for vectors, maps and sets
hugely improved the readability of lisp - look at how hashes work in
emacs lisp if you want to know what i mean. I was very excited about
`dosync` and a bit worried by the lack of leightweight erlang style
actors even though I could see how `agent`'s could help in that regard.
As I'll point out later on, we ended up not using these features at all
anyhow.

### The task at hand

When I joined Smallrivers to work on [paper.li](http://paper.li), it
became natural to choose clojure. The team was small and I felt
comfortable with it. There was a huge amount of work which needed to be
started quickly so a "full-stack" language was necessary to avoid
spreading across too many languages and technologies, and another
investigation in how the other languages had evolved in the meantime was
not possible. The main challenges to tackle were:

-   Being able to aggregate more content
-   Improve the quality of the processing done on content
-   Scaling the storage cluster accordingly
-   Automate the infrastructure

### The "hiring" problem

One thing that always pops up in discussions about somewhat marginal
languages is the hiring aspect, and the fear that you won't be able to
find people if you "lock" yourself in a language decision that strays
from the usual suspects. My experience is that when you tackle big
problems, that go beyond simple execution but require actual strong
engineers, hiring **will** be a problem, there's just no way around it.
Choosing people that fit your development culture and see themselves fit
to tackle big problems is a long process, integrating them is also time
consuming. In that picture, the chosen language isn't a huge deciding
factor.

I see marginal languages as a problem in the following organisations:

-   Companies tackling smaller problems, or problems already solved.
    These are right in choosing standard languages, if I built a team to
    build an e-commerce site I wouldn't go to clojure.
-   Larger companies which want their employees to jump from project to
    project, which makes sense from a managerial standpoint.

### What we built

The bulk of what was done revolves around these functional items:

-   A platform automation tool, built on top of
    [pallet](http://palletops.com).
-   Clojure facades for the tools relied upon (elastic search,
    cassandra, redis, kafka).
-   An ORM-type layer on top of cassandra
-   Our backend pipelines
-   A REST API

I won't go in too much detail on our in-house code, but rather reflect
on how things went over.

### Coding style and programming "culture"

One of the advantages of lisp, is that it doesn't have much syntax to go
around, so our rules stay simple:

-   the standard 2 space indent
-   we try to stick to 80 columns, because i'm that old
-   we always use `require` except for: **clojure.tools.logging** and
    **pallet.thread-expr** which are `use`'d
-   we avoid macros whenever possible
-   we use dynamically rebindable symbols

Of course we embraced non mutable state everywhere possible, which in
our case is almost everywhere. Whenever we need to checkpoint state, it
usually goes to our storage layer, not to in memory variables.

When compared to languages such as C, I was amazed at how little rules
are needed to enforce a consistent code look across projects, with very
little time needed to dive into a part written by someone else.

### The tools

1.  Local environment

    We didn't settle on a unique tool-suite at the office, when picking
    up clojure I made the move from vim to emacs because the integration
    is better and I fell in love with
    [paredit](http://emacswiki.org/emacs/ParEdit). Spread amongst the
    rest of team, textmate, eclipse and intellij were used.

    For building projects, leiningen was an obvious choice. I think
    leiningen is a great poster child for the greatest in clojure. A
    small and intelligent facade on top of maven, hiding all the
    annoying part of maven while keeping the nice distribution part.

    For continuous integration, we wrote a small bridge between
    leiningen and zi [lein-zi](https://github.com/smallrivers/lein-zi)
    which outputs pom.xml for maven, which are then used to build the
    clojure projects. We still hope to find some time to write a
    leiningen plugin for jenkins.

2.  Asynchronous programming

    Since a good part of what paper.li does relies on aggregation, async
    programming is very important. In the pure clojure world, the only
    real choice for async programming is
    [lamina](https://github.com/ztellman/lamina) and
    [aleph](https://github.com/ztellman/aleph). To be honest, aleph
    turned out to be quite the challenge, a combination of the amount of
    outbound connections that our work requires and the fact that aleph
    seems to initially target servers more than clients.

    Fortunately Zach Tellman put a lot of work into the library
    throughout last year and recent releases are more reliable. One very
    nice side effect of using a lisp to work with evented code is how
    readable code becomes, by retaining a sync like look.

    For some parts we still would directly go to a smaller netty facade
    if we were to start over, but that's a direct consequence of how
    much we learned along the way.

3.  Libraries not frameworks

    A common mantra in the clojure development community is that to ease
    integration the focus should be on libraries, not frameworks. This
    shows in many widespread projects such as compojure, pallet, and a
    host of common clojure tools. This proved very useful to us as
    clients of these libraries, allowing easy composition. I think
    pallet stands out most in that regard. Where most configuration
    management solutions offer a complete framework, pallet is just a
    library offering machine provisioning, configuration and command and
    control, which allowed us to integrate it with our app and build our
    abstractions on top of it.

    We tried to stick to that mantra in all of our work, building many
    small composable libraries, we made some errors at the beginning, by
    underutilizing some of clojure features, such as protocols but we
    now have good dynamics for writing these libraries, by writing the
    core of them with as little dependencies as possible, describing the
    behavior through protocols, and then writing add-ons which bring in
    additional dependencies and implement the protocol.

4.  Macros and DSLs

    Another common mantra is to avoid overusing macros. It can't be
    overstated how easy they make things though, our entity description
    library (which we should really prep up for public release, we've
    been talking about it for too long now) allows statements such as
    these (simplified):

    ```clojure
    (defentity :contributors
      (column :identifier (primary-key))
      (column :type (required))
      (column :name)
      (column :screen_name (index))
      (column :description)
      (column :detail (type :compound))

      (column :user_url)
      (column :avatar_url)
      (column :statuses_count (type :number))

      (has-many :articles)
      (has-many :editions (referenced false) (ttl 172800))
      (has-many :posts (key (timestamp :published_at)) (referenced false)))
    ```

    The power of DSLs in clojure cannot be understated, with a few
    macros you can easily build full languages, allowing easy extending
    of the functionality. Case in point, extracting text from articles,
    like most people we rely on a generic **readability** type library,
    but we also need to handle some sites that need special handling. By
    using a small DSL you can easily push rules that look like
    (simplified):

    ```clojure
    (defsiterule "some.obscure.site"
       [dom]
       (-> dom
           (pull "#stupid-article-id")))
    ```

    The great part is that you limit the knowledge to be transfered over
    to people writing the rules, you avoid intrusive changes to the core
    of your app and these can safely be pulled from an external
    location.

    At the end of the day, it seems to me as though the part of the
    clojure community that came from CL had awful memories of macros
    making code unreadable, but when sticking to macros with a common
    look and feel, i.e: `with-<resource>`, `def<resource>` type macros,
    there are huge succintness take aways without hindering readability
    or maintenance of the code.

5.  Testing

    Every respectable codebase is going to need at least a few test. I'm
    of the pragmatist church, and straight out do not believe in TDD,
    neither in crazy coverage ratios. Of course we still have a more
    that 95% unit test coverage and the decoupled approach preached by
    clojure's original developer, rich hickey[^1] allows for very
    isolated testing. For cases that require mocking, midge provides a
    nice framework and using it has created very fruitful throughout our
    code.

6.  Concurrency, Immutable State and Data Handling

    Funnily, we ended up almost never using any concurrency feature, not
    a single `dosync` made it in our codebase, few `atom`'s and a single
    `agent` (in <https://github.com/pyr/clj-statsd> to avoid recreating
    a Socket object for each datagram sent). We also banned `future`
    usage to more closely control our thread pools. Our usage of
    `atom`'s is almost exclusively bound to things that are write once /
    read many, in some cases we'd be better off with rebindable dynamic
    symbols.

    We rely on immutable state heavily though, and by heavily I actually
    mean exclusively. This never was a problem across the many lines of
    code we wrote, and helped us keep a sane and decoupled code base.

    With facades allowing to represent database fields, queue entries,
    and almost anything as standard clojure data structures and with
    powerful functions to work on them, complex handling of a large
    amount of data is very easily expressed. For this we fell in love
    with several tools which made things even easier:

    -   the threading operators `->` and `->>`
    -   the pallet thread-expr library which brings branching in
        threaded operations: `for->`, `when->`, and so on
    -   `assoc-in`, `update-in`, `seq-utils/index-by` and all these
        functions which allow easy transformation of data structs and
        retain a procedural look

    I cannot stress how helpful this has been for us in doing the
    important part of our code right and in a simple manner. This is
    clearly the best aspect of clojure as far as I'm concerned.

    Moreover, building on top of Java and with the current focus on "Big
    Data" everywhere, the interaction with large stores and tools to
    help building batch jobs are simply amazing, especially cascalog.

7.  The case of Clojurescript

    While very exciting we did not have a use for clojurescript, given
    the size of the existing JS codebase, and the willingness of the
    frontend developers to stick to a known.

    The simple existence of the project amazes me, especially with the
    promise of more runtimes, there are various implementations on top
    of lua, python and gambit (a scheme that compiles to C). With
    projects like cascalog, pallet, lein, compojure, noir and
    clojurescript, the ecosystem addresses all parts of almost any stack
    that you will be tempted to build and we didn't encounter cases of
    feeling cornered by the use of clojure - admiteddly, most of the
    time, a Java library came to the rescue.

8.  The community

    The community is very active, and has not reach critical mass yet,
    which makes its mailing-list and irc room still usable. There are
    many influent public figures, some who bring insight, some who bring
    beautiful code. Most are very open and available to discussion which
    shaped our approach of the language and our way of coding along the
    way.

### Closing words

It's been an exciting year and we're now a full fledged 80% clojure
shop. I'm very happy with the result, more so with the journey. I'm sure
we could have achieved with other languages as well. As transpires
throughout the article, the whole team feels that should we start over,
we would do it in clojure again.

It helped us go fast, adapt fast and didn't hinder us in any way. The
language seems to have a bright future ahead of it which is reassuring.
I would encourage people coming from python and ruby to consider it as a
transition language or as their JVM targetting language, since many
habits are still valid in clojure and since it helps slightly change the
way we look at problems which can then be reapplied in more
"traditional" languages.

[^1]: Rich hickey's talk simple made easy and his coining of the term
    "complecting" illustrates that
    <http://www.infoq.com/presentations/Simple-Made-Easy>
