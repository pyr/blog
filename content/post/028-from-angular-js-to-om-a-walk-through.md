#+title: From Angular.JS to Om: A walk-through
#+date: 2014-10-26
#+slug: from-angular-js-to-om-a-walk-through

A while back we did a small introductory talk on
[angular.js](https://angularjs.org) with
[@brutasse](https://twitter.com/brutasse). Our talk was aimed at backend
developers looking for a solution to build simple interfaces for REST
services. The app used exposed a simplistic job board with no
persistence and a mere three views.

Our main user-facing app at [exoscale](https://exoscale.ch) is an
angular one and we also have a large internal one. Most of our backend
work is done in clojure and python, with the occasional guest language.
While not discontent with angular, we kept a close eye on the promise of
fast updates with react's approach.

[om](https://github.com/swannodette) came at a time when we were looking
for alternatives for building interfaces with workloads involving plenty
of updates and interacting with server sent events.

While very happy with om so far, most of the introductory material out
there focuses on complex apps and features, and when starting out, we
fell like simple introductions were missing. Hopefully the following can
help bridge that gap, assuming a familiarity with the clojure language.

### Om in 3 minutes.

[om](https://github.com/swannodette/om) provides a thin layer of
abstraction on top of facebook's
[react.js](http://facebook.github.io/react) in
[clojurescript](https://github.com/clojure/clojurescript). om relies on
**[atoms](http://clojure.org/atoms)** to provide application state and
schedules renders on atom changes. This article does not dive in clojure
specifics. If you're new to the language past the basic language
elements, to get an understanding of what is going on you will need to
understand:

-   How [atoms](http://clojure.org/atoms) work and how to operate on
    them
-   How [protocols](http://clojure.org/protocols) work and how they may
    be implemented on the fly with
    [reify](http://clojure.github.io/clojure/clojure.core-api.html#clojure.core/reify).

When the global state stored in an atom is changed, the render phase
walks through a tree of **components** which are given all or part of
the state as input.

A very simple example would be:

``` clojure
(ns omg.frontend
  (:require [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]))

(def app-state
  (atom {:messages ["hello" "hello again" "bye"]}))

(defn message-list
  [app owner]
  (reify
    om/IRender
    (render [this]
      (apply dom/ul {} (for [m (:messages app)] (dom/li {} m))))))

(om/root message-list app-state
         {:target (. js/document (getElementById "app"))})
```

Three things happen in the above:

-   The app state is initialized to a list of messages.
-   A component is created which displays messages
-   The component is bound to the app state and mounted on the DOM.

The render phase uses available functions to create DOM elements, the
use of `apply` does not help make the component explicit, we will see
how to improve this later on.

The accompanying HTML can be as simple as:

``` html
<!doctype html>
<html lang="en">
  <body>
    <div id="app">
  </body>

  <script src="//cdnjs.cloudflare.com/ajax/libs/react/0.11.2/react.min.js"></script>
  <script src="/js/out/goog/base.js"></script>
  <script src="/js/app.js"></script>
  <script type="text/javascript">goog.require("omg.frontend");</script>
</html>
```

Compared to angular, om does not provide any standard way to structure
apps. This is in part due to the fact that om is fairly recent, but also
to the fact that the clojure community puts a bigger emphasis on
libraries than framework, which do not impose as much on their
consumers.

Fortunately, there are already plenty of librarie which help dealing
with common tasks. This project will use:

-   [sablono](https://github.com/r0man/sablono): provides a form based
    DSL to generate DOM elements
-   [cljs-ajax](https://github.com/JulianBirch/cljs-ajax): a simple AJAX
    client
-   [secretary](https://github.com/gf3/secretary): a library to help
    with routes within the frontend application
-   [om-tools](https://github.com/Prismatic/om-tools): a library which
    provides syntactic sugar for om components

### A note on building

In the clojure world, projects are built with
[leiningen](http://leiningen.org) and configured with a `project.clj`
file. To build the above project you would have a project structure like
this:

```
.
├── project.clj
├── resources
│   └── public
│       └── index.html
└── src
    └── omg
        └── frontend.cljs

4 directories, 3 files
```

Using the following `project.clj` file:

```clojure
(defproject omg "0.1.0"
  :description "demo om application"
  :dependencies [[org.clojure/clojure       "1.6.0"]
                 [org.clojure/clojurescript "0.0-2371" :scope "provided"]
                 [om                        "0.7.3"]]

  :plugins [[lein-cljsbuild "1.0.3"]]
  :cljsbuild {:builds
              {:app {:source-paths ["src"]
                     :compiler {:output-to     "resources/public/js/app.js"
                                :output-dir    "resources/public/js/out"
                                :source-map    "resources/public/js/out.js.map"
                                :optimizations :none
                                :pretty-print  true}}}})

```

### Extending our minimal app

To get a feel of the libraries we will be working with, let's dwell on
this example for a while and make it use some of the available
libraries. These can be added to the `:dependencies` vector in the
`project.clj` file:

``` clojure
[secretary                 "1.2.1"]
[sablono                   "0.2.22"]
[cljs-ajax                 "0.3.3"]
[prismatic/om-tools        "0.3.6"]                 
```

The first thing we can do is use sablono, which gives a familiar DSL for
building DOM elements, similar to hiccup:

``` clojure
(ns omg.frontend
  (:require [om.core      :as om :include-macros true]
            [sablono.core :as html :refer-macros [html]]))

(def app-state
  (atom {:messages ["hello" "hello again" "bye"]}))

(defn message-list
  [app owner]
  (reify
    om/IRender
    (render [this]
      (html [:ul (for [m (:messages app)] [:li m])]))))

(om/root message-list app-state
         {:target (. js/document (getElementById "app"))})
```

The next step is to use the syntactic sugar provided by **om-tools** to
create components:

``` clojure
(ns omg.frontend
  (:require [om.core       :as om]
            [om-tools.core :refer-macros [defcomponent]]
            [sablono.core  :as html :refer-macros [html]]))

(def app-state
  (atom {:messages ["hello" "hello again" "bye"]}))

(defcomponent message-list
  [app owner]
  (render [this] (html [:ul (for [m (:messages app)] [:li m])])))

(om/root message-list app-state
         {:target (. js/document (getElementById "app"))})
```

Our last step in this short introduction will be to build on the fact
that components can be nested and build on sub elements of the
application state (referred to as **cursors**):

``` clojure
(ns omg.frontend
  (:require [om.core       :as om]
            [om-tools.core :refer-macros [defcomponent]]
            [sablono.core  :as html :refer-macros [html]]))

(def app-state
  (atom {:messages ["hello" "hello again" "bye"]}))

(defcomponent message
  [m owner]
  (render [this] (html [:li m])))

(defcomponent message-list
  [app owner]
  (render [this] (html [:ul (om/build-all message (:messages app))])))

(om/root message-list app-state
         {:target (. js/document (getElementById "app"))})
```

### Structure of the app

The basic idea behind this article was to re-build the simple job board
we built in angular.js (<https://github.com/exoscale/angular-jobs>) with
om. The following principles were applied:

-   Show-case the use of **JSON** for interaction.
-   Assume we're talking to a simple RESTish API, not publishing app
    state diffs.
-   Try to separate concerns between views, model and router cleanly to
    recreate a known frontend app structure.

The app is built around 3 simple views:

-   A job listing view which can be filtered.
-   A job detail view.
-   A job post view.

To build this, the following will need to be built

-   A router to correctly dispatch based on location.
-   A service to listen to model changes.
-   Appropriate views to display contents.

As far as routes in the REST api are concerned, the following endpoints
are provided:

-   `GET /jobs`: retrieves a map of job id (a UUID) to a map containing
    a `title`, `company` and `desc` key to provide details on a job.
-   `POST /jobs`: expects a JSON body containing a map with the `title`,
    `company` and `desc` keys and will yield the augmented full map, as
    for the `GET` call.
-   `DELETE /jobs/:id`: will delete the job at key `id` from the map and
    return the modified map as for the `GET` call.

### A simple om router

One of the appealing things popular frontend frameworks offer is a
simple way to bind routes to controllers and views through a router.
This makes diving into an app explicit since the entry point for it
contains a collection of routes to look at.

In our equivalent angular.js app, this is what we had:

``` javascript
app.config(function($routeProvider) {
    $routeProvider
        .when('/list',        {templateUrl: 'listing.html', controller: 'Jobs'})
        .when('/details/:id', {templateUrl: 'details.html', controller: 'Jobs'})
        .when('/post',        {templateUrl: 'post.html',    controller: 'Jobs'})
        .otherwise(           {redirectTo:  '/list'});
});
```

Once you get past the syntax, the intent is clear. We are presented with
three routes which point to different templates and a single controller.

The story with om is slightly different, since the concepts of
controllers is totally separate from views and is only responsible for
modifying the global state atom which will trigger re-renders on views.

A router's duty will thus only be to map a route to the correct
component, and provide a way to jump to a different location within the
app programmaticaly.

[secretary](https://github.com/gf3/secretary) is a library which helps
with routing and understands the ubiquitous keyword based routes.
secretary provides two functions which we will be using:

-   `add-route!`: associates a function with a route path.
-   `dispatch!`: changes the location.

Since om's re-renders are only triggered by changes in the state, a
simple approach is to make the router be a component which dispatches to
the appropriate component based on the parsed route.

We can base our approach on a vector to configure the router's behavior:

``` clojure
(def routes
  ["/"        views/jobs
   "/job/:id" views/job
   "/post"    views/job-post]
```

If secretary handles setting a key in the application state designating
the current view and params, it becomes simple to build a component
which dispatches appropriately:

``` clojure
(defn init
  [routes app]

  ;; walk through provided routes, adding a callback
  ;; which updates the global state
  (doseq [[route view] (partition 2 routes)]
    (add-route! route #(swap! app assoc :router {:view view :params %})))

  ;; Yield a component which dispatches to the appropriate
  ;; component previously stored by our route callback
  (fn [app owner]
    (reify om/IRender
      (render [this] (om/build (get-in app [:router :view]) app)))))
```

Our job here is not completely done, we will also need to listen on
history events. Since om builds on top of google closure, we can use the
provided `History` object, here is the complete namespace with comments
eluded:

``` clojure
(ns jobs.router
  (:require [goog.events            :as events]
            [om.core                :as om]
            [goog.history.EventType :as EventType]
            [secretary.core         :refer [add-route! dispatch!]]
            [sablono.core           :refer-macros [html]])
  (:import goog.History))

(defonce history (History.))

(defn init [routes app]
  (doseq [[route view] (partition 2 routes)]
    (add-route! route #(swap! app assoc :router {:view view :params %})))

  (goog.events/listen history EventType/NAVIGATE #(-> % .-token dispatch!))
  (.setEnabled history true)

  (fn [app owner]
    (reify om/IRender
      (render [this] (om/build (get-in app [:router :view]) app)))))

(defn redirect [location]
  (.setToken history location))
```

The added `redirect` function will trigger an event type of `NAVIGATE`
which will dispatch to the appropriate route and thus update our global
state.

To wire in our router, we can then just build our main namespace by
using a router as the main component:

``` clojure
(ns jobs.frontend
  (:require [om.core       :as om]
            [jobs.views    :as views]
            [jobs.router   :as router]))

(defonce app-state (atom {}))

(def routes ["/"        views/jobs
             "/job/:id" views/job
             "/post"    views/job-post])

(let [router (router/init routes app-state)
      target {:target (. js/document (getElementById "app"))}]
  (om/root router app-state target))
```

With this it's now trivial to organize the application logically accross
several view components.

### The model

With dispatching to the appropriate view out of the way, the next
concern is building interaction with our API.

om build on top of [core.async](https://github.com/clojure/core.async)
to provide a flexible API to avoid relying exclusively on callbacks,
which we will leverage to build our model service. One of the core
components of **core.async** is it's **channel** interface which allows
seemingly separate threads of execution to have a simple communication
interface.

Our app only needs to be responsible for retrieval, creation and
deletion of jobs.

To simplify dealing with queries, we will leverage the
[cljs-ajax](https://github.com/JulianBirch/cljs-ajax) library. Here are
two simple examples of the API exposed by cljs-ajax:

``` clojure
(GET "/jobs" {:handler (fn [resp] ... )})

(POST "/jobs" {:params  {:title   "developer"
                         :company "supercorpo"
                         :desc    "..."}
               :handler (fn [resp] ...)
               :format  :json})
```

The simplest approach is to create a channel, looping on incoming
messages and taking the appropriate action.

We will define two message types:

-   `delete`: based on an ID will delete from
-   `post`: will expect a map of params and create a job

A map is a great container for these messages and will take either of
the following two forms:

``` clojure

;; delete message
{:type :delete :id "307b630c-76d0-4cdb-af3f-79acd135f508"}

;; post message
{:type :post :params {:title "developer" :company "foo" :desc "..."}}
```

A first version of the service can then be built like this:

``` clojure
(defn jobs [app]
  (let [in (chan)]
    ;; Get an initial state when starting the service.
    (GET "/jobs" {:handler #(swap! app assoc :jobs %)})

    (go-loop [{:keys [type id params]} (<! in)]
      (condp = type
        :delete (DELETE (str "/jobs/" id) {:handler #(swap app assoc :jobs %)})
        :post   (POST "/jobs" {:params  params
                               :handler #(swap app assoc :jobs %)
                               :format :json}))
        (recur (<! in)))
    in))
```

For each incoming message, based on the `:type` key within the message,
the appropriate action is taken. We can take things a step further and
prevent consumers of the model to have to deal with **core.async** by
adding convenience functions within the app state:

``` clojure
(defn sanitize
  [in [id {:strs [title company desc]}]]
  {:id id
   :delete! (fn [& _] (put! in {:type :delete :id id}))
   :title   title
   :company company
   :desc    desc})

(defn updater
  [in app jobs]
  (swap! app assoc :jobs (mapv (partial sanitize in) jobs)))

(defn jobs [app]
  (let [in      (chan)
        update! (partial updater in app)]
    ;; Get an initial state when starting the service.
    (GET "/jobs" {:handler update!})

    (go-loop [{:keys [type id params]} (<! in)]
      (condp = type
        :delete (DELETE (str "/jobs/" id) {:handler update!})
        :post   (POST "/jobs" {:params  params
                               :handler update!
                               :format :json}))
        (recur (<! in)))
    (swap! app assoc :create! #(put! in {:type :post :params %}))
    in))
```

This way we get nice idiomatic clojure vector of maps for jobs. Each job
map will contain keyword keys and contain a `delete!` function to kill
the element.

Wiring up our model in the main namespace is now as simple as:

``` clojure
(ns jobs.frontend
  (:require [om.core       :as om]
            [jobs.views    :as views]
            [jobs.model    :as model]
            [jobs.router   :as router]))

(defonce app-state (atom {}))

(def routes ["/"        views/jobs
             "/job/:id" views/job
             "/post"    views/job-post])

(model/jobs app-state)

(let [router (router/init routes app-state)
      target {:target (. js/document (getElementById "app"))}]
  (om/root router app-state target))
```

### View essentials

Let's look at how to build view components now. The simplest one is the
job detail view:

```clojure
(defcomponent job [app owner]
  (render [this]
    (let [id  (get-in app [:router :params :id])
          job (first (filter (comp (partial = id) :id) (:jobs app)))]
      (html
        [:h2 (:title job) " at " (:company job)]
        [:p (:desc job)]))))
```

Likewise, the job list view can be kept simple:

``` clojure
(defcomponent job-line [{:keys [title company url]} owner]
  (render [this]
    (html [:li [:a {:href url} title " at " company]])))

(defcomponent jobs [app owner]
  (render [this]
    (html [:ul (om/build-all job-line (:jobs app))])))
```

This is unfortunately not too helpful, the first thing that can be added
is a call to the `delete` function in `job-line`:

```clojure
(defcomponent job-line [{:keys [title company url delete!]} owner]
  (render [this]
    (html [:li
            [:a {:href url} title " at " company]
            [:button {:on-click delete!} "delete"]])))
```

### Life without two-way bindings

One thing om does not (and cannot, given the re-render approach) is the
oft-touted two-way bindings found in angular or ember. This means that
to handle input, events will need to be listened on and appropriately
propagated.

Let's first look at a sample filter for the job list, in order to
recreate the [list filter mechanism found in
angular](https://docs.angularjs.org/api/ng/filter/filter).

It would be a shame to store state that is component-local in the global
application state, fortunately, om provides a concept of local component
state, the render method use must be changed to `render-state` and an
optional `init-state` method may be provided.

We can then change our job list component to read:

```clojure
(defn build-predicate [owner event]
  (let [v (or (-> event .-target .-value) "")]
    (om/set-state! owner [:keep?]
                   (comp (partial re-find (re-pattern v))
                         :title))))
(defcomponent jobs
  [app owner]
  (init-state [this]
   {:keep? identity})
  (render-state [this state]
   (html
    [:div
     [:input {:on-change (partial build-predicate owner)}]
     [:ul
      (om/build-all job-line (filter (:keep? state) (:jobs app)))]])))
```

In the above, we keep a local predicate function in the component's
state which is initialized to `identity` to match all jobs.

When changes occur on the text input box, a predicate is built which
will be applied on the list for future renders.

The same approach can be taken to implement the form for posting new job
entries:

```clojure
(defn ev->state
  "Helper function to set local component state"
  [owner k event]
  (om/set-state! owner [k] (-> event .-target .-value)))

(defcomponent job-post
  [app owner]
  (render-state
   [this {:keys [title company desc] :as state}]
   (let [submit! (fn [& _]
                   ((:create! @app) state)
                   (redirect "/"))]
     (html
      [:form
       [:label "title"]
       [:input {:value title :on-change (partial ev->state owner :title)}]
       [:label "company"]
       [:input {:value company :on-change (partial ev->state owner :company)}]
       [:label "description"]
       [:textarea {:value desc :on-change (partial ev->state owner :desc)}]
       [:input {:type "submit" :on-click submit!}]]))))
```

### Wrapping up

With this, we conclude our whirlwind tour of om, paving the way for
structured apps. The complete application is available at
<https://github.com/pyr/om-jobs>, with the only notable difference being
views relying on [twitter bootstrap](http://getbootstrap.com).

While building on om definitely requires a bigger ramp-up than angular,
we have been very happy with this simple approach for routing and model
services. Once these basic building blocks are out of the way, we've
found om to be rather more straightforward and composable than angular.

Our biggest public om application is now the frontend for
[warp](https://github.com/pyr/warp), with more coming!
