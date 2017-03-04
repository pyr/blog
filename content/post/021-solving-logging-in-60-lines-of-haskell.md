#+title: Solving Nginx logging in 60 lines of Haskell
#+date: 2013-11-27

Nginx is well-known for only logging to files and being unable to log to
syslog out of the box.

There are a few ways around this, one that is often proposed is creating
named pipes (or FIFOs) before starting up nginx. Pipes have the same
properties than regular files in UNIX (to adhere to the important notion
that everything is a file in UNIX), but they expect data written to them
to be consumed by another process at some point. To compensate for the
fact that consumers might sometimes be slower than producers they
maintain a buffer of readily available data, with a hard maximum of 64k
in Linux systems for instance.

### Small digression: understanding linux pipes max buffer size

It can be a bit confusing to figure out what the exact size of FIFO
buffer is in linux. Our first reflex will be to look at the output of
`ulimit`

```bash
# ulimit -a
core file size          (blocks, -c) 0
data seg size           (kbytes, -d) unlimited
scheduling priority             (-e) 30
file size               (blocks, -f) unlimited
pending signals                 (-i) 63488
max locked memory       (kbytes, -l) unlimited
max memory size         (kbytes, -m) unlimited
open files                      (-n) 1024
pipe size            (512 bytes, -p) 8
POSIX message queues     (bytes, -q) 819200
real-time priority              (-r) 99
stack size              (kbytes, -s) 8192
cpu time               (seconds, -t) unlimited
max user processes              (-u) 63488
virtual memory          (kbytes, -v) unlimited
file locks                      (-x) unlimited
```

Which seems to indicate that the available pipe size in bytes is
`512 * 8`, amounting to 4kb. Turns out, this is the maximum atomic size
of a payload on a pipe, but the kernel reserves several buffers for each
created pipe, with a hard limit set in
<https://git.kernel.org/cgit/linux/kernel/git/torvalds/linux.git/tree/include/linux/pipe_fs_i.h?id=refs/tags/v3.13-rc1#n4>.

The limit turns out to be `4096 * 16`, amounting to 64kb, still not
much.

### Pipe consumption strategies

Pipes are tricky beasts and will bite you if you try to consume them
from syslog-ng or rsyslog without anything in between. First lets see
what happens if you write on a pipe which has no consumer:

```bash
$ mkfifo foo
$ echo bar > foo
```

bash

That's right, having no consumer on a pipe results in blocking writes
which will not please nginx, or any other process which expects logging
a line to a file to be a fast operation (and in many application will
result in total lock-up).

Even though we can expect a syslog daemon to be mostly up all the time,
it imposes huge availability constraints on a system daemon that can
otherwise safely sustain short availability glitches.

### A possible solution

What if instead of letting rsyslog do the work we wrapped the nginx
process in with a small wrapper utility, responsible for pushing logs
out to syslog. The utility would:

-   Clean up old pipes
-   Provision pipes
-   Set up a connection to syslog
-   Start nginx in the foreground, while watching pipes for incoming
    data

The only requirement with regard to nginx's configuration is to start it
in the foreground, which can be enabled with this single line in
`nginx.conf`:

```bash
daemon off;
```

### Wrapper behavior

We will assume that the wrapper utility receives a list of command line
arguments corresponding to the pipes it has to open, if for instance we
only log to `/var/log/nginx/access.log` and `/var/log/nginx/error.log`
we could call our wrapper - let's call it `nginxpipe` - this way:

```bash
nginxpipe nginx-access:/var/log/nginx/access.log nginx-error:/var/log/nginx/error.log
```

Since the wrapper would stay in the foreground to watch for its child
nginx process, integration in init scripts has to account for it, for
ubuntu's upstart this translates to the following configuration in
`/etc/init/nginxpipe.conf`:

```bash
respawn
exec nginxpipe nginx-access:/var/log/nginx/access.log nginx-error:/var/log/nginx/error.log
```

### Building the wrapper

For once, the code I'll show won't be in clojure since it does not lend
itself well to such tasks, being hindered by slow startup times and
inability to easily call OS specific functions. Instead, this will be
built in haskell which lends itself very well to system programming,
much like go (another more-concise-than-c system programming language).

First, our main function:

```haskell
main = do
  mainlog <- openlog "nginxpipe" [PID] DAEMON NOTICE
  updateGlobalLogger rootLoggerName (setHandlers [mainlog])
  updateGlobalLogger rootLoggerName (setLevel NOTICE)
  noticeM "nginxpipe" "starting up"
  args <- getArgs
  mk_pipes $ map get_logname args
  noticeM "nginxpipe" "starting nginx"
  ph <- runCommand "nginx"
  exit_code <- waitForProcess ph
  noticeM "nginxpipe" $ "nginx stopped with code: " ++ show exit_code
```

We start by creating a log handler, then using it as our only log
destination throughout the program. We then call `mk_pipes` which will
look on the given arguments and finally start the nginx process and wait
for it to return.

The list of argument given to `mk_pipes` is slightly modified, it
transforms the initial list consisting of

```haskell
[ "nginx-access:/var/log/nginx/access.log", "nginx-error:/var/log/nginx/error.log"]
```

into a list of string-tuples:

```haskell
[("nginx-access","/var/log/nginx/access.log"), ("nginx-error","/var/log/nginx/error.log")]
```

To create this modified list which just map our input list with a simple
function:

```haskell
is_colon x = x == ':'
get_logname path = (ltype, p) where (ltype, (_:p)) = break is_colon path
```

Next up is the pipe creation, since Haskell has no loop we use tail
recursion to iterate on the list of tuples:

```haskell
mk_pipes :: [(String,String)] -> IO ()
mk_pipes (pipe:pipes) = do
  mk_pipe pipe
  mk_pipes pipes
mk_pipes [] = return ()
```

The bulk of work happens in the `mk_pipe` function:

```haskell
mk_pipe :: (String,String) -> IO ()
mk_pipe (ltype,path) = do
  safe_remove path
  createNamedPipe path 0644
  fd <- openFile path ReadMode
  hSetBuffering fd LineBuffering
  void $ forkIO $ forever $ do
    is_eof <- hIsEOF fd
    if is_eof then threadDelay 1000000 else get_line ltype fd
```

The intersting bit in that function is the last 3 lines, where we create
a new "IO Thread" with `forkIO` inside which we loop forever waiting for
input for at most 1 second, logging to syslog when new input comes in.

The two remaining functions `get_line` and `safe_remove` have very
simple definitions, I intentionnaly left a small race-condition in
`safe_remove` to make it more readable:

```haskell
safe_remove path = do
  exists <- doesFileExist path
  when exists $ removeFile path

get_line ltype fd = do
  line <- hGetLine fd
  noticeM ltype line
```

I'm not diving into each line of the code, there is plenty of great
litterature on haskell, I'd recommmend "real world haskell" as a great
first book on the language.

I just wanted to show-case the fact that Haskell is a great alternative
for building fast and lightweight system programs.

### The awesome part: distribution!

The full source for this program is available at
<https://github.com/pyr/nginxpipe>, it can be built in one of two ways:

-   Using the cabal dependency management system (which calls GHC)
-   With the GHC compiler directly

With cabal you would just run:

```bash
cabal install --prefix=/somewhere
```

Let's look at the ouput:

```bash
$ ldd /somewhere/bin/nginxpipe 
linux-vdso.so.1 (0x00007fffe67fe000)
librt.so.1 => /usr/lib/librt.so.1 (0x00007fb8064d8000)
libutil.so.1 => /usr/lib/libutil.so.1 (0x00007fb8062d5000)
libdl.so.2 => /usr/lib/libdl.so.2 (0x00007fb8060d1000)
libpthread.so.0 => /usr/lib/libpthread.so.0 (0x00007fb805eb3000)
libgmp.so.10 => /usr/lib/libgmp.so.10 (0x00007fb805c3c000)
libm.so.6 => /usr/lib/libm.so.6 (0x00007fb805939000)
libgcc_s.so.1 => /usr/lib/libgcc_s.so.1 (0x00007fb805723000)
libc.so.6 => /usr/lib/libc.so.6 (0x00007fb805378000)
/lib64/ld-linux-x86-64.so.2 (0x00007fb8066e0000)
$ du -sh /somewhere/bin/nginxpipe
1.9M /somewhere/bin/nginxpipe
```

That's right, no crazy dependencies (for instance, this figures out the
correct dependencies across archlinux, ubuntu and debian for me) and a
smallish executable.

Obviously this is not a complete solution as-is, but quickly adding
support for a real configuration file would not be a huge endeavour,
where for instance an alternative command to nginx could be provided.

Hopefully this will help you consider haskell for your system
programming needs in the future!
