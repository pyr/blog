#+title: Beyond SSL client cert authentication: authorization
#+date: 2014-01-26

In a [previous
article](/entries/2013/05/30/neat-trick-using-puppet-as-your-internal-ca),
I tried to make the case for using a private certificate authority to
authenticate access to internal tools with SSL client certificates.

This approach is perfect to secure access to the likes of
[kibana](http://www.elasticsearch.org/overview/kibana),
[riemann-dash](https://github.com/aphyr/riemann-dash),
[graphite](http://graphite.wikidot.com) or similar tools.

If you start depending more and more on client-side certificates, you're
bound to reach the point when you need to tackle authorization as well.

While well-known, it is perfectly feasible to do so while keeping your
private CA as a single source of internal user management.

I will be assuming a private CA authenticates clients for sites
accessing `app.priv.example.com` and that three SSL client certificates
exist: `alice.users.example.com`, `bob.users.example.com`,
`charlie.users.example.com` (as mentionned above, see
[here](/entries/2013/05/30/neat-trick-using-puppet-as-your-internal-ca)
for a quick way to get up and running).

Now since our certificates bear the names of clients what we need to do
is retrieve the certificate's name. Assuming that you have a web
application exposed through HTTP which [nginx](http://nginx.org) proxies
over to, here are the relevant bits that need to be added.

```
proxy_set_header X-Client-Verify $ssl_client_verify;
proxy_set_header X-Client-DN $ssl_client_s_dn;
proxy_set_header X-SSL-Issuer $ssl_client_i_dn;
```

Let's go over them one by one:

-   `$ssl_client_verify`: Can be set to **SUCCESS**, **FAILED** or
    **NONE**.
-   `$ssl_client_s_dn`: Will be set to the Subject DN of the client
    cert.
-   `$ssl_client_i_dn`: Will be set to the Issuer DN of the client cert.

As far as configuration is concerned, this is all that is needed. There
are more variables that you can tap into if necessary refer to the nginx
[http\_ssl module
documentation](http://nginx.org/en/docs/http/ngx_http_ssl_module.html)
for an exhaustive list. If you rely on the apache webserver, similar
environment variables are available as documented
[here](http://httpd.apache.org/docs/2.2/mod/mod_ssl.html).

Within applications, you'll receive the identity of clients in this
format and can thus be retrieved with a regexp:

```
CN=bob.users.example.com
```

It's now dead simple to tie in to your application. Here is a simple
ring middleware which attaches the calling user to incoming requests.

```clojure
(defn wrap-ssl-client-auth [handler]
  (fn [request]
    (let [ssl_cn   (get-in request [:headers "X-Client-DN"])
          [_ user] (re-find #"CN=(.*)\.users\.example\.com$" ssl_cn)]
      (handler (assoc request :user user)))))
```
