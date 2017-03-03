#+title: Using Riemann to monitor python apps
#+date: 2013-05-21

Another quick blog post today to show-case usage of riemann for in-app
reporting. This small blurb will push out a metric for each wrap route
with the metric name you provide.

When handlers raise exceptions, a metric is sent out as well with the
exception's message as description.

```python
{{< highlight python >}}
import socket
import time
import bernhard

# [...]

def wrap_riemann(metric,
                 client=bernhard.Client(),
                 tags=['python']):
    def riemann_decorator(f):
        @wraps(f)
        def decorated_function(*args, **kwargs):

            host = socket.gethostname()
            started = time.time()
            try:
                response = f(*args, **kwargs)
            except Exception as e:
                client.send({'host': host,
                             'service': metric + "-exceptions",
                             'description': str(e),
                             'tags': tags + ['exception'],
                             'state': 'critical',
                             'metric': 1})
                raise

            duration = (time.time() - started) * 1000
            clientsend({'host': host,
                        'service': metric + "-time",
                        'tags': tags + ['duration'],
                        'metric': duration})
            return response
        return decorated_function
    return riemann_decorator
{{</ highlight >}}
```

Provided you have a [flask](http://flask.pocoo.org) app for instance you
could then have use the wrapper in the following way:

```python
{{< highlight python >}}
app = Flask(__name__)
riemann = bernhard.Client()

@app.route('/users')
@wrap_riemann('list-users', client=riemann)
def list_users():
  # [...]

@app.route('/users/<id>', methods=['DELETE'])
@wrap_riemann('delete-user', client=riemann)
def delete_users():
  # [...]
{{</ highlight >}}
```

In riemann we can easily massage these events to give us points worth
looking at:

-   A per route and overall mean request time
-   A per route and overall per second exception gauge

```clojure
{{< highlight clojure >}}
;; start-up servers
(tcp-server :host "0.0.0.0")
(udp-server :host "0.0.0.0")

(def graph (graphite {:host "your.graphite.host"}))
(def index (default {:state "ok" :ttl 60} (update-index (index))))

(periodically-expire 5 index)

(streams

  ;; we're interested in events coming from the python web app
  (tagged "python"

    ;; aggregate duration event for each interval of one second
    ;; then compute the mean before sending of to indexer and grapher
    (tagged "duration"

      ;; We split by service to get one metric per route
      (by [:service]
        (fixed-time-window 1
           (combine folds/mean index graph)))

      ;; unsplitted, we'll get an overall metric
      (with {:service "overall-mean-duration"}
        (fixed-time-window 1
           (combine folds/mean index graph))))

    ;; sum exceptions per second
    (tagged "exception"
      (by [:service]
        (rate 1 index graph))
      (with {:service "overall-exceptions"}
        (rate 1 index graph)))))
{{</ highlight >}}
```

Obviously this wrapper would work just as well for any python function.
As a ways

I extended a bit on this idea and released a cleaned up version here:
<https://github.com/exoscale/python-riemann-wrapper>.
