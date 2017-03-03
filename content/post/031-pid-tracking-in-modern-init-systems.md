#+title: PID tracking in modern init systems
#+date: 2014-11-09

Wherever your init daemon preference may go to, if you're involved in
writing daemons you're currently faced with the following options:

-   Start your software in the foreground and let something handle it
-   Write a [huge
    kludge](https://github.com/exceliance/haproxy/blob/master/scripts/haproxy.init.debian)
    of shell script which tries to keep track of the daemons PID
-   Work hand in hand with an init system which can be hinted at the
    service's PID

Why is tracking daemon process IDs hard ? Because as a parent, you don't
have many options to watch how your children processes evolve, the only
reliable way is to find a way for the children to send back their PID in
some way.

Traditionally this has been done with PID files which are usually
subject to being left. **upstart** has a mechanism to do tracking which
is notorious for its ability to lose track of the real child PID and
which leaves init in a complete screwed up state, with the original
side-effect of hanging machines during shutdown.

Additionaly, to provide efficient sequencing of daemon startups,
traditional init scripts resort to sleeping for arbitrary periods to
ensure daemons are started while having no guarantee as to the actual
readyness of the service.

In this article we'll explore two ways of enabling daemons to coexist
with **upstart** and **systemd**.

### Upstart and `expect stop`

**upstart** has four modes of launching daemons:

-   A simple mode where the daemon is expected to run in the foreground
-   `expect fork` which expects the daemon process to fork once
-   `expect daemon` which expects the daemon process to fork twice
-   `expect stop` which waits for any child process to stop itself

When using `expect stop`, by tracking childs for SIGSTOP signals,
**upstart** is able to reliably determine which PID the daemon lives
under. When launched from **upstart**, the `UPSTART_JOB` environment
variable is set, which means that it suffices to check for it. This also
gives a good indication to the daemon that it should stay in the
foreground:

```c
const char    *upstart_job = getenv("UPSTART_JOB");


  if (upstart_job != NULL)
    raise(SIGSTOP); /* wait for upstart to start us up again */
  else {

    switch ((pid = fork())) {
    case -1:
      /* handle error */
      exit(1);
    case 0:
      /* we're in the parent */
      return 0;
    default:
      break;
    }

    setsid();
    close(2);
    close(1);
    close(0);

    if (open("/dev/null", O_RDWR) != 0)
      err(1, "cannot open /dev/null as stdin");
    if (dup(0) != 1)
      err(1, "cannot open /dev/null as stdout");
    if (dup(0) != 2)
      err(1, "cannot open /dev/null as stderr");
  }
```

This is actually all there is to it as far as **upstart** is concerned.
**Upstart** will catch the signal and register the PID it came from as
the daemon. This way there is no risk of **upstart** losing track of the
correct PID.

Let's test our daemon manually:

```bash
$ env UPSTART_JOB=t $HOME/mydaemon
$ ps auxw | grep mydaemon
pyr      22702  0.0  0.0  22044  1576 ?        Ts   21:21   0:00 /home/pyr/mydaemon
```

The interesting bit here is that the reported state contains `T` for
stopped. We can now resume execution by issuing:

```bash
kill -CONT 22702
```

Now configuring your daemon in upstart just needs:

```bash
expect stop
respawn
exec /home/pyr/mydaemon
```

### Systemd and `sd_notify`

**Systemd** provides a similar facility for daemons, although it goes a
bit further at the expense of an increased complexity.

**Systemd**'s approach is to expose a UNIX datagram socket to daemons
for feedback purposes. The payload is composed of line separated key
value pairs, where keys may be either one of:

-   `READY`: indicate whether the service is ready to operate.
-   `STATUS`: update the status to display in systemctl's output.
-   `ERRNO`: in the case of failure, hint at the reason for failure.
-   `BUSERROR`: DBUS style error hints.
-   `MAINPID`: indicate which PID the daemon runs as.
-   `WATCHDOG`: when perusing the watchdog features of **systemd**, this
    signal will reset the watchdog timestamp.

A word of advice, if you plan on using the error notification mechanism,
it would be advisable to pre-allocate a static buffer to be able to send
out messages even in out-of-memory situations.

Like **upstart**, **systemd** sets an environment variable:
`NOTIFY_SOCKET` to allow conditional behavior. It's up to the daemon to
include its PID in the message payload, so it doesn't matter whether
forking happens or not.

**Systemd** is happy with both forking and foreground-running daemons, a
simple recommendation could be to only daemonize when neither
`UPSTART_JOB` nor `NOTIFY_SOCKET` are present:

```c
  const char    *upstart_job = getenv("UPSTART_JOB");
  const char    *systemd_socket = getenv("NOTIFY_SOCKET");

/* ... */

  if (upstart_job != NULL)
    raise(SIGSTOP); /* wait for upstart to start us up again */
  else if (notify_socket != NULL)
    sd_notify(0, "READY=1\nSTATUS=ready\nMAINPID=%lu\n",
              getpid())
  else
    /* daemonize ... */
```

The use of `sd_notify` requires linking to `libsystemd`, if you want to
keep dependencies to a minimum, you also have the possibility of
crafting the payload directly and sending a single UDP datagram to the
socket stored in the `NOTIFY_SOCKET` environment variable. Here's an
implementation from Vincent Bernat's LLDPD:
<https://github.com/vincentbernat/lldpd/blob/abc042057d9fc237b239948136cb89a4a2ac9a01/src/daemon/lldpd.c#L1233-L1276>

To configure your **systemd** *unit*, you'll now need to mark your job
as having the type `notify`:

```ini
[Unit]
Description=My daemon
Documentation=man:mydaemon(8)
After=network.target

[Service]
Type=notify
NotifyAccess=main
ExecStart=/home/pyr/mydaemon

[Install]
WantedBy=multi-user.target
```
