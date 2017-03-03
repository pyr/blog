#+title: Why were there gotos in apple software in the first place?
#+date: 2014-02-25

A recent vulnerability in iOS and Mac OS can boils down to a double goto
resulting in making critical ssl verification code unreachable.

```c
hashOut.data = hashes + SSL_MD5_DIGEST_LEN;
hashOut.length = SSL_SHA1_DIGEST_LEN;
if ((err = SSLFreeBuffer(&hashCtx)) != 0)
    goto fail;
if ((err = ReadyHash(&SSLHashSHA1, &hashCtx)) != 0)
    goto fail;
if ((err = SSLHashSHA1.update(&hashCtx, &clientRandom)) != 0)
    goto fail;
if ((err = SSLHashSHA1.update(&hashCtx, &serverRandom)) != 0)
    goto fail;
if ((err = SSLHashSHA1.update(&hashCtx, &signedParams)) != 0)
    goto fail;
    goto fail;
if ((err = SSLHashSHA1.final(&hashCtx, &hashOut)) != 0)
    goto fail;

/* ... */

fail:
    SSLFreeBuffer(&signedHashes);
    SSLFreeBuffer(&hashCtx);
    return err;
```

Since the fail label return err, no error is reported in normal
conditions, making the lack of verification silent.

### But gotos are bad, lol

With all the talk about goto being bad (if you haven't, read **Edsger
Dijkstra**'s famous [go to considered
harmful](http://www.u.arizona.edu/~rubinson/copyright_violations/Go_To_Considered_Harmful.html)),
it's a wonder it could still be found in production code. In this short
post I'd like to point out that while `goto` is generally a code smell,
it has one very valid and important use in Ansi C: exception handling.

Let's look at a simple function that makes use of **goto exception
handling**:

```c
char *
load_file(const char *name, off_t *len)
{
    struct stat  st;
    off_t            size;
    char            *buf = NULL;
    int            fd;

    if ((fd = open(name, O_RDONLY)) == -1)
        return (NULL);
    if (fstat(fd, &st) != 0)
        goto fail;
    size = st.st_size;
    if ((buf = calloc(1, size + 1)) == NULL)
        goto fail;
    if (read(fd, buf, size) != size)
        goto fail;
    close(fd);

    *len = size + 1;
    return (buf);

fail:
    if (buf != NULL)
        free(buf);
    close(fd);
    return (NULL);
}
```

Here goto serves a few purposes:

-   keep the code intent clear
-   reduce condition branching
-   allow graceful failure handling

While this excerpt is short, it would already be much more awkward to
repeat the failure handling code in the body of the `if` statement
testing for error conditions.

A more complex example shows how multiple types of "exceptions" can be
handled with `goto` without falling into the spaghetti code trap.

```c
void
ssl_read(int fd, short event, void *p)
{
    struct bufferevent  *bufev = p;
    struct conn           *s = bufev->cbarg;
    int                      ret;
    int                      ssl_err;
    short                      what;
    size_t                   len;
    char                       rbuf[IBUF_READ_SIZE];
    int                      howmuch = IBUF_READ_SIZE;

    what = EVBUFFER_READ;

    if (event == EV_TIMEOUT) {
        what |= EVBUFFER_TIMEOUT;
        goto err;
    }

    if (bufev->wm_read.high != 0)
        howmuch = MIN(sizeof(rbuf), bufev->wm_read.high);

    ret = SSL_read(s->s_ssl, rbuf, howmuch);
    if (ret <= 0) {
        ssl_err = SSL_get_error(s->s_ssl, ret);

        switch (ssl_err) {
        case SSL_ERROR_WANT_READ:
            goto retry;
        case SSL_ERROR_WANT_WRITE:
            goto retry;
        default:
            if (ret == 0)
                what |= EVBUFFER_EOF;
            else {
                ssl_error("ssl_read");
                what |= EVBUFFER_ERROR;
            }
            goto err;
        }
    }

    if (evbuffer_add(bufev->input, rbuf, ret) == -1) {
        what |= EVBUFFER_ERROR;
        goto err;
    }

    ssl_bufferevent_add(&bufev->ev_read, bufev->timeout_read);

    len = EVBUFFER_LENGTH(bufev->input);
    if (bufev->wm_read.low != 0 && len < bufev->wm_read.low)
        return;
    if (bufev->wm_read.high != 0 && len > bufev->wm_read.high) {
        struct evbuffer *buf = bufev->input;
        event_del(&bufev->ev_read);
        evbuffer_setcb(buf, bufferevent_read_pressure_cb, bufev);
        return;
    }

    if (bufev->readcb != NULL)
        (*bufev->readcb)(bufev, bufev->cbarg);
    return;

retry:
    ssl_bufferevent_add(&bufev->ev_read, bufev->timeout_read);
    return;

err:
    (*bufev->errorcb)(bufev, what, bufev->cbarg);
}
```

One could wonder why functions aren't used in lieue of goto statements
in this context, it boils down to two things: context and efficiency.

Since the canonical use case of goto is for a different termination path
that handles cleanup it needs context - i.e: local variables - that
would need to be carried to the cleanup function this would make for
proliferation of awkward specific functions.

Additionaly, functions create additional stack frames which in some
scenarios may be a concern especially in the context of kernel
programming, and critical path functions.

### The take-away

While there is a general sentiment that the goto statement should be
avoided, which is mostly valid, it's not a hard rule and there is, in C,
a very valid use case that only goto provides.

In the case of the apple code, the error did not stem from the use of
the goto statement but on an unfortunate typo.

It's interesting to note that **Edsger Dijkstra** wrote his original
piece at a time where conditional and loop constructs such if/then/else
and while where not available in mainstream languages such as Basic. He
later clarified his initial statement, saying:

> Please don't fall into the trap of believing that I am terribly
> dogmatical about \[the goto statement\]. I I have the uncomfortable
> feeling that others are making a religion out of it, as if the
> conceptual problems of programming could be solved by a single trick,
> by a simple form of coding discipline.

words of wisdom.
