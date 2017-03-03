#+title: An adventure with clocks, component, and clojure.spec
#+date: 2017-01-09
#+slug: an-adventure-with-clocks-component-and-spec

I have long parted with my initial, [lacking approach to component
handling](http://spootnik.org/entries/2014/01/25_poor-mans-dependency-injection-in-clojure.html)
in Clojure. I now rely on [Stuart
Sierra](https://twitter.com/stuartsierra)'s
[component](https://github.com/stuartsierra) library for this.

In this short post, I want to showcase how this library helps structure
code around clear functional boundaries and allows testing without
having to depend on mocking. This might induce building components for
seemingly innocuous code. I will also dive into `clojure.spec` to show
how it helps writing automated tests on top of correct generated inputs.

This article was initially written as a litterate programming [org
mode](http://orgmode.org) file, If you edit the
[source](https://github.com/pyr/blog/blob/master/sources/entries/2017-01-09-an-adventure-with-clocks-component-and-spec.org)
you can use `C-c v t` to generate a single file which can be used as an
executable ****boot**** script, which means you will need to have
[boot](http://boot-clj.com) installed in order to execute this.

I used ****boot**** here because it is easy to build a standalone
executable script with it. Be sure to have `BOOT_CLOJURE_VERSION` set to
`1.9.0-alpha14`, since `clojure.spec` is only available from 1.9.0
onward.

To start we will add a shebang line to make sure that boot is invoked to
run this script.

    #!/usr/bin/env boot

For the purpose of this article, we will only use a few dependencies:

``` clojure
(set-env! :dependencies '[[com.stuartsierra/component "0.3.1"]
                          [org.clojure/test.check     "0.9.0"]])
```

For the purpose of this article, we will be building request signing
functionality. Since this is a standalone ****boot**** project test
namespaces are pulled here as well:

``` clojure
(ns request.signing
   (:require [com.stuartsierra.component :as component]
             [clojure.test :refer :all]
             [clojure.test.check.generators :as tgen]
             [clojure.spec :as s]
             [clojure.spec.gen :as gen]
             [clojure.spec.test :as st])
   (:import javax.crypto.Mac javax.crypto.spec.SecretKeySpec))
```

Our request signing functionality will work on incoming requests which
look like this:

``` clojure
{:timestamp     1483805460         ;; UNIX Epoch of request
 :payload       "some-command"     ;; Request payload
 :authorization {:key       "..."
                 :signature "..."}}
```

Provided each user is given an API key, and an API secret, we can define
the request signing mechanism to be:

``` python
signature = hexadecimal_string(hmac_256(secret-key, timestamp + payload))
```

Factoring the request timestamp in the signing mechanism provides a good
protection against replay attacks: by ensuring that requests come-in
within a reasonable time-delta (let's say 500ms). To implement this a
first implementation could be based on two components:

-   A ****keystore**** component which maps API keys to API secrets
-   A ****signer**** component which signs a payload

We can do away with the ****keystore**** component here, rely on a map,
or something that behaves like a map. (If you want to investigate how to
build map-like constructs, there is an
[article](http://spootnik.org/entries/2014-11-06-playing-with-clojure-core-interfaces.html)
describing how to do that). I won't describe here how to build an
alternate implementation which would look-up keys in a database, but it
is rather straightforward.

As far as signing is concerned, interacting with the JVM is required. To
avoid pulling-in additional dependencies, we use the `javax.crypto`
available classes:

``` clojure
(defn bytes->hex [bytes]
  (reduce str (map (partial format "%02x") bytes)))

(defn sign-string [secret-key payload]
  (let [key (SecretKeySpec. (.getBytes secret-key) "HmacSHA256")]
    (-> (doto (Mac/getInstance "HmacSHA256")
          (.init key))
        (.doFinal (.getBytes payload))
        (bytes->hex))))
```

We now have all necessary bits to write a first authorization function.
Here is a first version without the addition of components for now:

``` clojure
(defn request-signature [keystore request]
  (when-let [secret (get keystore (get-in request [:authorization :api-key]))]
    (sign-string secret (str timestamp payload))))

(defn authorized-request? [keystore equest]
  (when-let [signature (request-signature keystore request)]
    (= (get-in request [:authorization :signature]) signature)))
```

This already gives us a lot of safety: a stolen secret key does not
allow signing arbitrary requests as would a simple key/token validation
approach, commonly found in API implementations.

One thing this authorization scheme is subject to though is replay
attacks, a stolen signed payload can be replayed at will.

To limit this risk, we can rely on good wall clocks to ensure that
requests are sent within a reasonable timeframe, which we can store as
an option:

``` clojure
(def max-delta-ms 500)
```

We can then write our updated auhtorization function. Note how here we
made `authorized-request?` use a `Authorizer` as its input. This can be
safely done, since started component get their depencies provided.

``` clojure
(defn authorized-timestamp? [timestamp]
  (let [now (System/currentTimeMillis)]
    (<= (- timestamp max-delta-ms) now (+ timestamp max-delta-ms))))

(defn request-signature [keystore request]
  (when-let [secret (get keystore (get-in request [:authorization :api-key]))]
    (sign-string secret (str (:timestamp request) (:payload request)))))

(defrecord Authorizer [keystore])

(defn authorized-request? [{:keys [keystore]} request]
  (when-let [signature (request-signature keystore request)]
    (and (= (get-in request [:authorization :signature]) signature)
         (authorized-timestamp? (:timestamp request)))))
```

This solution will provide a good layer of security while being secure
enough for most practical purposes. Going one step further would involve
guaranteeing no replay attack can be performed by handing-out a one-time
token for each request. We will not describe this scheme in this
article.

While complete, the solution is now hard to test, since it relies on a
wall clock. There are three approaches to testing we can take:

-   Good old `sleep` calls which are a safe way of having spurious test
    errors :-)
-   Mocking wall clock calls
-   Making the clock a component

It does seem overkill to build a specific clock component for the
standard behavior of a wall clock which just reaches out to the system.

``` clojure
(defprotocol Clock  (now! [this]))
(defrecord WallClock [] Clock (now! [this] (System/currentTimeMillis)))
```

With this simple protocol we can now build our complete component
system. This will be quite similar to the previous presented
implementation, with the exception that the `Authorizer` component now
depends on `clock` as well and will use both in `authorized-request?`.

``` clojure
(defn authorized-timestamp? [clock timestamp]
  (<= (- timestamp max-delta-ms) (now! clock) (+ timestamp max-delta-ms)))

(defn request-signature [keystore request]
  (when-let [secret (get keystore (get-in request [:authorization :api-key]))]
    (sign-string secret (str (:timestamp request) (:payload request)))))

(defrecord Authorizer [clock keystore])

(defn authorized-request? [{:keys [keystore clock]} request]
  (when-let [signature (request-signature keystore request)]
    (and (= (get-in request [:authorization :signature]) signature)
         (authorized-timestamp? clock (:timestamp request)))))

```

Our resulting system will thus be a three-component one:

-   A ****clock**** component which will give the current time.
-   A ****keystore**** component to look-up the secret key corresponding
    to an API key.
-   An ****authorizer**** component, used to authorize incoming
    requests, relying on the two above components.

We can then imagine building the system like this:

``` clojure
(defn start-system [secret-keys]
   (-> (component/system-map :keystore   secret-keys
                             :clock      (->WallClock)
                             :authorizer (map->Authorizer {}))
       (component/system-using {:authorizer [:clock :keystore]})
       (component/start-system)))
```

With this, everything necessary for authorizing requests is available,
but there are no tests yet. If we were to rely on this implementation
for tests, we would have to play with timing for test purposes:

``` clojure
(deftest simple-signing
  (let [sys (start-system {:foo "ABCDEFGHIJK"})]
    (doseq [cmd ["start-engine" "thrust" "stop-engine"]]
      (let [request {:timestamp (now! (:clock sys))
                     :payload       cmd
                     :authorization {:api-key :foo}}
            signed  (assoc-in request [:authorization :signature]
                              (request-signature (:keystore sys) request))]
        (is (authorized-request? sys signed))
        (Thread/sleep 600)
        (is (not(authorized-request? sys signed)))))))
```

This is unfortunately brittle and does not lend itself easily to a large
number of tests since it relies on sleep.

Thanks to our component-based approach we can now write an alternate
clock:

``` clojure
(defrecord RefClock [state] Clock (now! [_] @state))
```

Once we have our new clock, we can adapt the start system function:

``` clojure
(defn start-system [secret-keys time]
   (-> (component/system-map :keystore   secret-keys
                             :clock      (if time (->RefClock time) (->WallClock))
                             :authorizer (map->Authorizer {}))
       (component/system-using {:authorizer [:clock :keystore]})
       (component/start-system)))
```

This new clock can then be used for our tests, doing away with brittle
sleep calls and paving the way for generative tests.

``` clojure
(deftest simple-signing
  (let [time (atom 0)
        sys  (start-system {:foo "ABCDEFGHIJK"} time)]
    (doseq [cmd ["start-engine" "thrust" "stop-engine"]]
      (let [request {:timestamp (now! (:clock sys))
                     :payload       cmd
                     :authorization {:api-key :foo}}
            signed  (assoc-in request [:authorization :signature]
                              (request-signature (:keystore sys) request))]
        (is (authorized-request? sys signed))
        (swap! time + max-delta-ms 1) 
        (is (not(authorized-request? sys signed)))))))
```

While this is nice, it only tests a very small subset of input. To go
beyond this, we can reach out to `clojure.spec` to give us compile-time
guarantees that we are using correct types for our functions and to
allow building generative tests.

In a few instances, we help generators by providing a set of known
values. We start off by forcing every generated `keystore` instance to
be:

``` clojure
{:foo "ABCDEFGH"
 :bar "IJKLMNOP"}
```

Generated `api-key` instances will also always be either `:foo` or
`:bar`. `Clock` instance generation is bound to a `RefClock` instance as
well.

Let's look at the code in detail. We start by defining a few predicates
to make our specs a bit easier to understand:

``` clojure
(def lookup?           #(instance? clojure.lang.ILookup %))
(def clock?            #(satisfies? Clock %))
(def not-empty-string? #(not= "" %))
(def sig-bytes?        #(= 32 (count %))) ;; Number of bytes in a signature
(def valid-sig-width?  #(= 64 (count %)))
(def valid-sig-chars?  #(re-matches #"^[0-9a-f]+$" %))
```

Next we can define data types for every plain and compound type we have
created:

``` clojure
(s/def ::keystore lookup?)
(s/def ::clock clock?)
(s/def ::authorizer (s/keys :req-un [::keystore ::clock]))
(s/def ::signature (s/and string? valid-sig-width? valid-sig-chars?))
(s/def ::api-key keyword?)
(s/def ::authorization (s/keys :req-un [::api-key] :opt-un [::signature]))
(s/def ::timestamp int?)
(s/def ::secret-key (s/and string? not-empty-string?))
(s/def ::payload (s/and string? not-empty-string?))
(s/def ::request (s/keys :req-un [::timestamp ::payload ::authorization]))
(s/def ::bytes (s/and bytes? sig-bytes?))
```

I like to also provide separate specs for argument lists:

``` clojure

(s/def ::auth-request? (s/cat :authorizer ::authorizer :request ::request))
(s/def ::request-signature (s/cat :keystore ::keystore :request ::request))
(s/def ::auth-timestamp? (s/cat :clock ::clock :timestamp ::timestamp))
(s/def ::sign-string (s/cat :secret-key ::secret-key :payload string?))
(s/def ::bytes->hex (s/cat :bytes ::bytes))
(s/def ::now! (s/cat :block ::clock))
```

We can now use the above types to specify our functions. Nothing
extraordinary here if you have already used `spec`.

``` clojure
(s/fdef bytes->hex :args ::bytes->hex :ret ::signature)
(s/fdef sign-string :args ::sign-string :ret ::signature)
(s/fdef now! :args ::now! :ret ::timestamp)
(s/fdef authorized-timestamp? :args ::auth-timestamp? :ret boolean?)
(s/fdef request-signature :args ::request-signature :ret ::signature)
(s/fdef authorized-request? :args ::auth-request? :ret boolean?)
```

We are now fully specified and using `instrument` will allow verifying
functions are called properly.

The complex bit is to go from here to tests which use generators for
building sensible data. Relying on the provided generators will not cut
it as they would not be able to build `clock` and `keystore` instances,
nor would they be able to provide sensible `timestamp` or `signature`
values.

This is most obvious in `request` which contains co-dependent
information, since the `:signature` field in the `:authorization` map
depends on the payload and timestamp of the request. Likewise, testing
`authorized-timestamp?` relies on having a solid way of generating
timestamp, which we built our `Clock` protocol for.

Fortunately, `spec` allows overriding generators. We can start by
building simple generators for values we want picked from a narrow set,
this is for instance the case for our `keystore` and related api keys:

``` clojure
(def fake-keystore {:foo "ABCDEFGH" :bar "IJKLMNOP"})
(def fake-time     (atom 0))
(def fake-clock    (->RefClock fake-time))

(defn keystore-gen [] (s/gen #{fake-keystore}))
(defn api-key-gen  [] (s/gen (set (keys fake-keystore))))
(defn clock-gen    [] (s/gen #{fake-clock}))
```

We can test out this generators on the repl:

``` clojure
(gen/sample (s/gen (s/with-gen ::api-key api-key-gen)))
(gen/sample (s/gen (s/with-gen ::clock-gen clock-gen)))
```

To instrument `bytes->hex` we will need a way of generating 32 wide byte
arrays. Since there is no such generator, we will need to compose the
creation of a 32-width vector and its coercion to a byte array:

``` clojure
(defn bytes-gen    [] (gen/fmap byte-array (gen/vector tgen/byte 32)))
```

In the above we use `byte` from `clojure.test.check.generators` since no
such generator exists in `clojure.spec.gen`.

Only the most complex generator remains, `request-gen` for building
request maps. If we look at our base building blocks, here is what we
need to build a correct request map:

-   A ****keystore**** to sign the request
-   A ****clock**** to get a correct timestamp
-   A random ****api-key****
-   A random ****payload****

Once we have these elements we can transform them into a correct
request. We will use `fmap` again here, and split out request generation
in two functions:

``` clojure
(defn sign-request [[ks req]]
   (assoc-in req [:authorization :signature] (request-signature ks req)))

(defn build-request [{:keys [clock payload keystore api-key]}]
  (vector
    keystore
    {:timestamp     (now! clock)
     :payload       payload
     :authorization {:api-key api-key}}))

(defn request-gen []
  (gen/fmap
    (comp sign-request build-request)
    (s/gen (s/keys :req-un [::clock ::keystore ::api-key ::payload])
           {::clock clock-gen ::keystore keystore-gen ::api-key api-key-gen})))
```

We now have a solid way of generating requests, we can again test it on
the repl:

``` clojure
(gen/sample (s/gen (s/with-gen ::request request-gen)))
```

Now that we have good generation available, we can write automated
testing for all of our functions. We can do this by enumerating all
testable symbols in the current namespace and running generative testing
on them, supplying our list of generator overrides. This involves
checking that the result is true for all test outputs generated by
`clojure.spec.test/check`:

``` clojure
(def gen-overrides {::keystore      keystore-gen
                    ::clock         clock-gen
                    ::api-key       api-key-gen
                    ::bytes         bytes-gen
                    ::request       request-gen})

(deftest generated-tests
  (doseq [test-output (-> (st/enumerate-namespace 'request.signing)
                          (st/check {:gen gen-overrides}))]
    (testing (-> test-output :sym name)
      (is (true? (-> test-output :clojure.spec.test.check/ret :result))))))
```

To go, one last step further, we can supply a different function spec to
our most important function, `authorized-request?` to make sure that
given all provided inputs, our authorizer determined the request to be
authorized:

``` clojure
(deftest specialized-tests
   (testing "authorized-request?"
      (is (true? (-> (st/check-fn authorized-request?
                                  (s/fspec :args ::auth-request? :ret boolean?)
                                  {:gen gen-overrides})
                     :clojure.spec.test.check/ret
                     :result)))))
```

Last, we run all tests:

``` clojure
(run-tests 'request.signing)
```

I'd like to thank [Max Penet](https://twitter.com/mpenet) and [Gary
Fredericks](https://twitter.com/gfredericks_) for their valuable input
while writing this.
