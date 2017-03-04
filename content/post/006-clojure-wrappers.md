#+date: 2011-08-04
#+title: Clojure Wrappers

### Functions in the wild

Functional programming rears its head in the most unusual places in
software design. Take the web stack interfaces (rack[^1], plack[^2],
WSGI[^3]), they all implement a very functional view of what a web
application is: a function that takes a map as input and returns a map
as output.

Middleware then build on that abstraction composing themselves down to
the actual handler call.

The graylog2 extension mechanism is a good example of this, the bulk of
which is really simple:

```ruby
def _call(env)
  begin
    # Call the app we are monitoring
    @app.call(env)
  rescue Exception => err
    # An exception has been raised. Send to Graylog2!
    send_to_graylog2(err)

    # Raise the exception again to pass backto app.
    raise
  end
end
```

All the code does is wrap application call in an exception catching
block and returning the original result. Providing such a composition
interface enables doing two useful things for middleware applications:

-   Taking action before the handler is called, for instance parsing
    json data as arguments.
-   Taking action after the handler was called, in this case catching
    exceptions.

### Composition in clojure

The russian doll approach taken in rack was a natural fit for clojure's
web stack, ring[^4]. What I want to show here is how easy it is to write
a simple wrapping layer for any type of function, enabling building
simple input and output filters for any type of logic.

### The basics

Let's say we have a simple function interacting with a library, taking a
map as parameter, yielding an operation status map back:

```clojure
(defn send-command
  "send a command"
  [payload]
  (-> payload
     serialize     ; translate into a format for on-wire
     send-sync     ; send command and wait for answer
     deserialize)) ; translate result back as map
```

Now let's say we need the following filters:

-   We need certain keys in the command map sent out
-   We want to provide defaults for the reply map
-   We want to time command execution for statistics usage

The functions are easy to write:

```clojure
(defn filter-required [payload]
  (let [required [:user :operation]] 
    (when (some nil? (map payload required))
      (throw (Exception. "invalid payload"))))
  payload)

(defn filter-defaults [response]
  (let [defaults {:status :unknown, :user :guest}]
    (merge defaults response)))

(defn time-command [payload]
  (let [start-ts    (System/nanoTime)
        response    (send-command payload)
        end-ts      (System/nanoTime)]
    (merge response {:elapsed (- end-ts start-ts)})))
```

Now all that is required is linking those functions together. A very
naive approach would be to go the imperative way, with let:

```clojure
(defn linking-wrappers [payload]
  (let [payload  (filter-required payload)
        payload  (filter-defaults payload)
        response (time-command payload)]
    response))
```

### Evolving towards a wrapper interface

Thinking about it in a more functional way, it becomes clear that this
is just threading the payload through functions. Clojure even has a nice
macro that does just that.

```clojure
(defn composing-wrappers [payload]
  (-> payload filter-required filter-defaults time-command))
```

This is already very handy, but needs a bit of work when we want to move
the filters around, or if we wanted to be able to provide the filters as
a list, even though using **loop** and **recur** it seems feasible.

One of the gripes of such an approach is that you need two types of
middleware functions, those that happen before and those that happen
after an action, writing a generic timing filter that can be plugged in
anywhere would involve writing two filter functions!

The other gripe is that there is no way to bypass the execution of the
filter chain, except by throwing exceptions, what we want is to wrap
around the command call to be able to interfere with the processing.

Looking back on the **rack** approach, we see that the call to the
actual rack handler is enclosed within the middleware, doing the same in
clojure would involve returning a function wrapping the original call,
which is exactly what has been done for ring[^5], by the way:

```clojure
(defn filter-required [handler]
  (fn [payload]
    (let [required [:user :operation]] 
      (if (some nil? (map payload required))
        {:status :fail :message "invalid payload"}
        (handler payload)))))

(defn filter-defaults [handler]
  (fn [payload]
    (let [defaults {:status :unknown, :user :guest}]
      (handler (merge defaults payload)))))

(defn time-command [handler]
  (fn [payload]
    (let [start-ts    (System/nanoTime)
          response    (handler payload)
          end-ts      (System/nanoTime)]
      (merge response {:elapsed (- end-ts start-ts)}))))
```

Reusing the threading operator, building the composed handler is now
dead easy:

```clojure
(def composed (-> send-command
                  time-command
                  filter-defaults
                  filter-required))
```

### Tying it all together

We have now reached the point where composition is very easy, at the
expense of a bit of overhead when writing wrappers.

The last enhancement that could really help is being able to provide a
list of functions to decorate a function with which would yield the
composed handler.

We cannot apply to **-&gt;** since it is a macro, so we call **loop**
and **recur** to the rescue:

```clojure
(defn wrap-with [handler all-decorators]
  (loop [cur-handler  handler
         decorators   all-decorators]
    (if decorators
      (recur ((first decorators) cur-handler) (next decorators))
      cur-handler)))
```

Or as **scottjad** noted:

```clojure
(defn wrap-with [handler all-decorators]
  (reduce #(%2 %1) handler all-decorators))
```

Now, you see this function has no knowledge at all of the logic of
handlers, making it very easy to reuse in a many places, writing
composed functions is now as easy as:

```clojure
(def wrapped-command
  (wrap-with send-command [time-command filter-defaults filter-required]))
```

I hope this little walkthrough helps you navigate more easily through
projects such as ring, compojure, and the likes. You'll see that in many
places using such a mechanism allows elegant test composition.

[^1]: <http://rack.rubyforge.org/>

[^2]: <http://plackperl.org/>

[^3]: <http://wsgi.org/wsgi/>

[^4]: <https://github.com/mmcgrana/ring>

[^5]: <https://github.com/mmcgrana/ring>
