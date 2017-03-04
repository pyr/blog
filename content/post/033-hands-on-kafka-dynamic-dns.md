#+title: Hands on Kafka: Dynamic DNS
#+date: 2015-04-23

I [recently
wrote](/entries/2015/03/10/simple-materialized-views-in-kafka-and-clojure)
about kafka [log
compaction](https://cwiki.apache.org/confluence/display/KAFKA/Log+Compaction)
and the use cases it allows. The article focused on simple key-value
storage and did not address going beyond this. In practice, values
associated with keys often need more than just bare values.

To see how log compaction can still be leveraged with more complex
types, we will see how to approach maintaining the state of a list in
kafka through the lens of a dynamic DNS setup.

### DNS: the 30 second introduction

I assume my readers are familiar with the architecture of the *Domain
Name System* (**DNS**). To summarize, DNS revolves around the notions of
zones, separated by dots which follow a tree like hierachy starting at
the right-most zone.

![Hierarchy](/media/hands-on-kafka/hierarchy.png)

Each zone is responsible for maintaining a list of records. Records each
have a type and an associated payload. Here's a non-exhaustive list of
record types:

  Record    Content
  --------- ---------------------------------------------------------
  `SOA`     Start of Authority. Provides zone details and timeouts.
  `NS`      Delegates zone to other nameservers.
  `A`       Maps a record to an IPv4 address.
  `AAAA`    Maps a record to an IPv6 address.
  `CNAME`   Aliases a record to another.
  `MX`      Mail server responsibility for a record.
  `SRV`     Arbitrary service responsibility for a record.
  `TXT`     Arbitrary text associated with record.
  `PTR`     Maps an IP record with a zone.

Given this hierarchy and properties, DNS can be abstracted to a hash
table, keyed by zone. Value contents can be considered lists.

```javascript
{
  "exoscale.com": [
    {record: "api", type: "A", content: "10.0.0.1"},
    {record: "www", type: "A", content: "10.0.0.2"},
  ],
  "google.com": [
    {record: "www", type: "A", content: "10.1.0.1"}
  ]
}
```

In reality, zone contents are stored in zone files, whose content look
roughly like this:

```
$TTL  86400 
$ORIGIN example.com.
@  1D  IN  SOA ns1.example.com. hostmaster.example.com. (
               2015042301 ; serial
               3H ; refresh
               15 ; retry
               1w ; expire
               3h ; minimum
               )
IN  NS  ns1.example.com.     ; nameserver
IN  NS  ns2.example.com.     ; nameserver
IN  MX  10 mail.example.com. ; mail provider
; server host definitions
ns1    IN  A      10.0.0.1
ns1    IN  A      10.0.0.2
mail   IN  A      10.0.0.10
www    IN  A      10.0.0.10
api    IN  CNAME  www
```

Based on our mock list content above, generating a correct DNS zone file
is a simple process.

### Dynamic DNS motivation

Dynamic DNS updates greatly help when doing any of the following:

-   Automated zone synchronisation based on configuration management.
-   Automated zone synchronisation based on IaaS content.
-   Authorized and authenticated programmatic access to zone contents.

Most name servers support fast reloads and convergence of configuration,
but still require generating zone files on the fly and reloading
configuration. Kafka can be a very valid choice to maintain a stream of
changes to zones.

### Storing zone changes in Kafka

Updates to DNS zone usually trickle in as invidual record changes. An
evident candidate for topic keys is the actual zone name. As far as
changes are concerned it makes sense to store the individual record
changes, not the whole zone on each change. Kafka payloads could thus be
standard operations on lists:

  Operation   Effect
  ----------- ----------------- --
  `ADD`       Create a record
  `SET`       Update a record
  `DEL`       Delete a record

Each operation modifies the state of the list and reading from the head
of a log for a particular key ensures that a correct, up to date version
of a zone can be recreated:

![Topic](/media/hands-on-kafka/topic.png)

### Taking advantage of log compaction

While this is fully functional, the only correct compaction method for
the above approach is time based, and requires reading from the head of
the log. A simple way to address this issue is to create a second topic,
meant to hold full zone snapshots, associated with the offset at which
the snapshot was done. This allows to use log compaction on the snapshot
topic.

With this approach, starting a consumer from scratch only requires two
operations:

-   Read the snapshot log from its head.
-   Read the update log, only considering entries which are more recent
    than the associated snapshot time.

![Dual Topic](/media/hands-on-kafka/dualtopic.png)

For this approach to work, a single property must remain true: snapshots
emitted on the snapshot topic should be more frequent than the
expiration on the update topic.

### Similar use-cases

Beyond **DNS**, this approach is valid for all standard compound types
and their operations:

-   **Stacks**: `push`, `pop`
-   **Lists**: `add`, `del`, `set`
-   **Maps**: `set`, `unset`
-   **Sets**: `add`, `del`

