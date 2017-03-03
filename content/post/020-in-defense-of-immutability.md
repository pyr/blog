#+title: In defense of immutability
#+date: 2013-11-22

I thought I'd whip together a short blog post as a reaction to Mark
Burgess' ([@markburgess\_osl](https://twitter.com/markburgess_osl))
keynote at the Cloudstack Collaboration Conference.

I thoroughly enjoyed the keynote but was a bit taken aback by one of the
points that was made. It can be summed up in a quote that has since been
floating around:

<blockquote class="twitter-tweet" lang="en"><p><a href="https://twitter.com/search?q=%23ccceu13&amp;src=hash">#ccceu13</a> <a href="https://twitter.com/markburgess_osl">@markburgess_osl</a> no such thing .. immutable system .. show me a sys that is not changing and I&#39;ll show u a sys that is powered off</p>&mdash; botchagalupe (@botchagalupe) <a href="https://twitter.com/botchagalupe/statuses/403811430971211776">November 22, 2013</a></blockquote> <script async src="//platform.twitter.com/widgets.js" charset="utf-8"></script>
Mark digressed and dissed the notion that there is such a thing as
**immutable infrastructure** and that it should be a goal to strive for.
He supported this argument by saying that applications that live and
perform a function or deliver a service can not be immutable - lest they
are not running.

I think a few things are misguided in Mark's analysis of what is meant
by immutable infrastructure

### Immutable infrastructure semantics

What people refer to when promoting immutable infrastructure is
predominantly the promotion of immutable data structure to represent and
converge infrastructure. In now way does it mean that systems are viewed
as immobile.

It is also a realization that many parts of infrastructure can now be
treated as fully stateless automatons and for which lifecycle management
can partly happen at a different level than previously namely by
replacing instance instead of reconverging the state of an existing
system.

### Immutability promotes persistent data structures and a better audit trail

Since immutable data structure enforce building new copies of data on
change, instead of silently mutating state, it provides a better avenue
to create a complete history of state for data.

One of the main thing immutable data provides is a consistent view of
data at a certain point in time - which again, does not limit its
ability to evolve over time. This property is key in building simple
audit trails.

For one thing, if immutability was such a limiting factor, I don't think
so many programming languages would be built around it.

### Immutable infrastructure does not conflict with configuration management

Many people now turning to this new way to think about lifecycle
management of infrastructure and systems as a whole come from years of
experience with configuration management and in no way are trying to get
rid of it. It is more a reflexion on what conf management is today,
where it happens and how it could evolve.

### TL;DR

-   Immutable data structures can help improve the way we describe
    infrastructure and system components
-   Nobody thinks of systems as pure (in the functional sense)
    functions, environment matters
-   Immutable infrastructure refers to a consistent view at a certain
    point in time, changes means new copies
-   Configuration management still has a predominant place when striving
    for immutable infrastructure

I'll now go read Mark's book which he pitched rather well, except for
this minor nitpick (ok that and the cheapshot at lambda calculus, but I
won't cover that!) :-)
