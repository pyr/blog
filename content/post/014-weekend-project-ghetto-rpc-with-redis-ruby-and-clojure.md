#+title: Weekend project: Ghetto RPC with redis, ruby and clojure
#+date: 2012-11-11

There's a fair amount of things that are pretty much set on current
architectures. Configuration management is handled by chef, puppet (or
pallet, for the brave). Monitoring and graphing is getter better by the
day thanks to products such as collectd, graphite and riemann. But one
area which - at least to me - still has no obvious go-to solution is
command and control.

There are a few choices which fall in two categories: ssh for-loops and
pubsub based solutions. As far as ssh for loops are concerned,
capistrano (ruby), fabric (python), rundeck (java) and pallet (clojure)
will do the trick, while the obvious candidate in the pubsub based space
is mcollective.

[Mcollective](https://github.com/puppetlabs/marionette-collective) has a
single transport system, namely STOMP, preferably set-up over RabbitMQ.
It's a great product and I recommend checking it out, but two aspects of
the solution prompted me to write a simple - albeit less featured -
alternative:

-   There's currently no other transport method than STOMP and I was
    reluctant to bring RabbitMQ into the already well blended technology
    mix in front of me.
-   The client implementation is ruby only.

So let me here engage in a bit of NIHilism and describe a redis based
approach to command and control.

The scope of the tool would be rather limited and only handle these
tasks:

-   Node discovery and filtering
-   Request / response mechanism
-   Asynchronous communication (out of order replies)

### Enter redis

To allow out of order replies, the protocol will need to broadcast
requests and listen for replies separately. We will thus need both a
pub-sub mechanism for requests and a queue for replies.

While redis is initially an in-memory key value store with optional
persistence, it offers a wide range of data structures (see the full
list at <http://redis.io>) and pub-sub support. No explicit queue
function exist, but two operations on lists provide the same
functionality.

Let's see how this works in practice, with the standard redis-client
`redis-cli` and assuming you know how to run and connect to a redis
server:

1.  Queue Example

    Here is how to push items on a queue named `my_queue`:

    ```bash
	{{< highlight bash >}}
    redis 127.0.0.1:6379> LPUSH my_queue first
    (integer) 1
    redis 127.0.0.1:6379> LPUSH my_queue second
    (integer) 2
    redis 127.0.0.1:6379> LPUSH my_queue third
    (integer) 3
	{{</ highlight >}}
    ```

    You can now subsequently issue the following command to pop items:

    ```bash
	{{< highlight bash >}}
    redis 127.0.0.1:6379> BRPOP my_queue 0
    1) "my_queue"
    2) "first"
    redis 127.0.0.1:6379> BRPOP my_queue 0
    1) "my_queue"
    2) "second"
    redis 127.0.0.1:6379> BRPOP my_queue 0
    1) "my_queue"
    2) "third"
	{{</ highlight >}}
    ```

    LPUSH as its name implies pushes items on the left (head) of a list,
    while BRPOP pops items from the right (tail) of a list, in a
    blocking manner, with a timeout argument which we set to 0, meaning
    that the action will block forever if no items are available for
    popping.

    This basic queue mechanism is the main mechanism used in several
    open source projecs such as logstash, resque, sidekick, and many
    others.

2.  Pub-Sub Example

    Queues can be subscribed to through the `SUBSCRIBE` command, you'll
    need to open two clients, start by issuing this in the first:

    ```bash
	{{< highlight bash >}}
    redis 127.0.0.1:6379> SUBSCRIBE my_exchange
    Reading messages... (press Ctrl-C to quit)
    1) "subscribe"
    2) "my_hub"
    3) (integer) 1
	{{</ highlight >}}
    ```

    You are now listening on the `my_exchange` exchange, issue the
    following in the second terminal:

    ```bash
	{{< highlight bash >}}
    redis 127.0.0.1:6379> PUBLISH my_exchange hey
    (integer) 1
	{{</ highlight >}}
    ```

    You'll now see this in the first terminal:

    ```bash
	{{< highlight bash >}}
    1) "message"
    2) "my_hub"
    3) "hey"
	{{</ highlight >}}
    ```

3.  Differences between queues and pub-sub

    The pub-sub mechanism in redis, broadcasts to all subscribers and
    will not queue up data for disconnect subscribers, where-as queues
    will deliver to the first available consumer, but will queue up (in
    RAM, so make sure of your consuming ability)

### Designing the protocol

With the following building blocks in place, a simple layered protocol
can be designed offering the following functionality, offering the
following workflow:

-   A control box broadcasts a requests with a unique ID (`UUID`), with
    a command and node specification
-   All nodes matching the specification reply immediately with a
    `START` status, indicating that the requests has been acknowledged
-   All nodes refusing to go ahead reply with a `NOOP` status
-   Once execution is finished, nodes reply with a `COMPLETE` status

Acknowledgments and replies will be implemented over queues, solely to
demonstrate working with queues, using pub-sub for replies would lead to
cleaner code.

If we model this around `JSON`, we can thus work with the following
payloads, starting with requests:

```javascript
{{< highlight javascript >}}
request = {
  reply_to: "51665ac9-bab5-4995-aa80-09bc79cfb2bd",
  match: {
    all: false, /* setting to true matches all nodes */
    node_facts: {
      hostname: "www*" /* allowing simple glob(3) type matches */
    }
  },
  command: {
    provider: "uptime",
    args: { 
     averages: {
       shortterm: true,
       midterm: true,
       longterm: true
     }
    }
  }
}
{{</ highlight >}}
```

`START` responses would then use the following format:

```javascript
{{< highlight javascript >}}
response = {
  in_reply_to: "51665ac9-bab5-4995-aa80-09bc79cfb2bd",
  uuid: "5b4197bd-a537-4cc7-972f-d08ea5760feb",
  hostname: "www01.example.com",
  status: "start"
}
{{</ highlight >}}
```

`NOOP` responses would drop the sequence UUID not needed:

```javascript
{{< highlight javascript >}}
response = {
  in_reply_to: "51665ac9-bab5-4995-aa80-09bc79cfb2bd",
  hostname: "www01.example.com",
  status: "noop"
}
{{</ highlight >}}
```

Finally, `COMPLETE` responses would include the result of command
execution:

```javascript
{{< highlight javascript >}}
response = {
  in_reply_to: "51665ac9-bab5-4995-aa80-09bc79cfb2bd",
  uuid: "5b4197bd-a537-4cc7-972f-d08ea5760feb",
  hostname: "www01.example.com",
  status: "complete",
  output: {
    exit: 0,
    time: "23:17:20",
    up: "4 days, 1:45",
    users: 6,
    load_averages: [ 0.06, 0.10, 0.13 ]
  }
}
{{</ highlight >}}
```

We essentially end up with an architecture where each node is a daemon
while the command and control interface acts as a client.

### Securing the protocol

Since this is a proof of concept protocol and we want implementation to
be as simple as possible, a somewhat acceptable compromise would be to
share an SSH private key specific to command and control messages
amongst nodes and sign requests and responses with it.

SSL keys would also be appropriate, but using ssh keys allows the use of
the simple `ssh-keygen(1)` command.

Here is a stock ruby snippet, gem which performs signing with an SSH
key, given a passphrase-less key.

```ruby
{{< highlight ruby >}}
require 'openssl'

signature = File.open '/path/to/private-key' do |file|
  digest = OpenSSL::Digest::SHA1.digest("some text")
  OpenSSL::PKey::DSA.new(file).syssign(digest)
end
{{</ highlight >}}
```

To verify a signature here is the relevant snippet:

```ruby
{{< highlight ruby >}}
require 'openssl'

valid? = File.open '/path/to/private-key' do |file|

  OpenSSL::PKey::DSA.new(file).sysverify("some text", sig)
end
{{</ highlight >}}
```

This implements the common scheme of signing a SHA1 digest with a DSA
key (we could just as well sign with an RSA key by using
`OpenSSL::PKey::RSA`)

A better way of doing this would be to sign every request with the
host's private key, and let the controller look up known host keys to
validate the signature.

### The clojure side of things

My drive for implementing a clojure controller is integration in the
command and control tool I am using to interact with a number of things.

This means I only did the work to implement the controller side of
things. Reading SSH keys meant pulling in the
[bouncycastle](http://www.bouncycastle.org/) libs and the apache
commons-codec lib for base64:

```clojure
{{< highlight clojure >}}
(import '[java.security                   Signature Security KeyPair]
        '[org.bouncycastle.jce.provider   BouncyCastleProvider]
        '[org.bouncycastle.openssl        PEMReader]
        '[org.apache.commons.codec.binary Base64])
(require '[clojure.java.io :as io])


(def algorithms {:dss "SHA1withDSA"
                 :rsa "SHA1withRSA"})

;; getting a public and private key from a path
(def keypair (let [pem (-> (PEMReader. (io/reader "/path/to/key")) .readObject)]
               {:public (.getPublic pem)
                :private (.getPrivate pem)}))

(def keytype :dss)

(defn sign
  [content]
  (-> (doto (Signature/getInstance (get algorithms keytype))
        (.initSign (:private keypair))
        (.update (.getBytes str)))
      (.sign)
      (Base64/encodeBase64string)))

(defn verify
  [content signature]
  (-> (doto (Signature/getInstance (get algorithms keytype))
        (.initVerify (:public keypair))
        (.update (.getBytes str)))
      (.verify (-> signature Base64/decodeBase64))))
{{</ highlight >}}
```

Redis support has several options, I used the `jedis` Java library which
has support for everything we're interested in.

### Wrapping up

I have early - read: with lots of room for improvements, and a few
corners cut - implementations of the protocol, both the agent and
controller code in ruby, and the controller code in clojure, wrapped in
my IRC bot in clojure, which might warrant another article.

The code can be found here: <https://github.com/pyr/amiral> (name
alternatives welcome!)

If you just want to try out, you can fetch the `amiral` gem in ruby, and
start an agent like so:

```bash
{{< highlight bash >}}
$ amiral.rb -k /path/to/privkey agent
{{</ highlight >}}
```

You can then test querying the agent through a controller:

```bash
{{< highlight bash >}}
$ amiral.rb -k /path/to/privkey controller uptime
accepting acknowledgements for 2 seconds
got 1/1 positive acknowledgements
got 1/1 responses
phoenix.spootnik.org: 09:06:15 up 5 days, 10:48, 10 users,  load average: 0.08, 0.06, 0.05
{{</ highlight >}}
```

If you're feeling adventurous you can now start the clojure controller,
it's configuration is relatively straightforward, but a bit more
involved since it's part of an IRC + HTTP bot framework:

```clojure
{{< highlight clojure >}}
{:transports {amiral.transport.HTTPTransport {:port 8080}
              amiral.transport.irc/create    {:host "irc.freenode.net"
                                              :channel "#mychan"}}
 :executors {amiral.executor.fleet/create    {:keytype :dss
                                              :keypath "/path/to/key"}}}
{{</ highlight >}}
```

In that config we defined two ways of listening for incoming controller
requests: IRC and HTTP, and we added an "executor" i.e: a way of doing
something.

You can now query your hosts through HTTP:

```bash
{{< highlight bash >}}
$ curl -XPOST -H 'Content-Type: application/json' -d '{"args":["uptime"]}' http://localhost:8080/amiral/fleet
{"count":1,
 "message":"phoenix.spootnik.org: 09:40:57 up 5 days, 11:23, 10 users,  load average: 0.15, 0.19, 0.16",
 "resps":[{"in_reply_to":"94ab9776-e201-463b-8f16-d33fbb75120f",
           "uuid":"23f508da-7c30-432b-b492-f9d77a809a2a",
           "status":"complete",
           "output":{"exit":0,
                     "time":"09:40:57",
                     "since":"5 days, 11:23",
                     "users":"10",
                     "averages":["0.15","0.19","0.16"],
                     "short":"09:40:57 up 5 days, 11:23, 10 users,  load average: 0.15, 0.19, 0.16"},
           "hostname":"phoenix.spootnik.org"}]}
{{</ highlight >}}
```

Or on IRC:

```
09:42 < pyr> amiral: fleet uptime
09:42 < amiral> pyr: waiting 2 seconds for acks
09:43 < amiral> pyr: got 1/1 positive acknowledgement
09:43 < amiral> pyr: got 1 responses
09:43 < amiral> pyr: phoenix.spootnik.org: 09:42:57 up 5 days, 11:25, 10 users,  load average: 0.16, 0.20, 0.17
```

### Next Steps

This was a fun experiment, but there are two outstanding problems which
will need to be addressed quickly

-   Tests test tests. This was a PoC project to start with, I should
    have known better and wrote tests along the way.
-   The queue based reply handling makes controller logic complex, and
    timeout handling approximate, it should be switched to pub-sub
-   The signing should be done based on known hosts' public keys instead
    of the shared key used now.
-   The agent should expose more common actions: service interaction,
    puppet runs, etc.

