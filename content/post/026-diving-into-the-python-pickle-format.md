#+title: Diving into the Python Pickle formatt
#+date: 2014-04-05

**pickle** is python's serialization format, able to freeze data, as
long as all leaves in class hierarchies are storeable. pickle falls into
the category of formats that I'm not a huge fan of. Like all
serialization formats heavily tied to a language, it makes interop
harder and pushes platform and language concerns all the way to the
storage layer.

You could do worse than look into Protobuf and Avro when looking for a
fast serialization format for your network protocol needs. When speed
isn't so much of an issue, EDN and JSON are also good candidates.

I had no choice but to look into pickle since I am writing a
compatibility layer for the carbon binary protocol in clojure within the
[cyanite](https://github.com/pyr/cyanite) project.

The work described in this article is available in
[pickler](https://github.com/pyr/pickler).

### Format definition

One of the first hurdles when investigating pickle is finding its
reference. I didn't find a formal format definition, but it ended up not
being that part to piece things together.

I started off looking at how graphite serializes metrics, the structure
is rather simple, and ends up looking like this:

``` python
metrics = [
  [ "web1.cpu0.user", [ 1332444075, 10.5 ] ],
  [ "web1.cpu1.user", [ 1332444076, 90.3 ] ]
]
```

We can now add in some code to write the metrics out:

``` python
pickle.dump(metrics, open("frozen.p", "wb"))
```

In addition to this, the best source seems obviously to be the
documentation and source code of the `pickletools` python modules (See
<https://docs.python.org/3.4/library/pickletools.html> and
<http://hg.python.org/cpython/file/087cdbf49e80/Lib/pickletools.py#l38>)

In addition to this, it's going to be useful to work with the hexdump of
the output data from above:

```
00000000: 8003 5d71 0028 5d71 0128 580e 0000 0077  ..]q.(]q.(X....w
00000010: 6562 312e 6370 7530 2e75 7365 7271 025d  eb1.cpu0.userq.]
00000020: 7103 284a ab7b 6b4f 4740 2500 0000 0000  q.(J.{kOG@%.....
00000030: 0065 655d 7104 2858 0e00 0000 7765 6231  .ee]q.(X....web1
00000040: 2e63 7075 312e 7573 6572 7105 5d71 0628  .cpu1.userq.]q.(
00000050: 4aac 7b6b 4f47 4056 9333 3333 3333 6565  J.{kOG@V.33333ee
00000060: 652e                                     e.
```

### A stack-based virtual machine.

To get a sense of how pickle works, a good approach is to use the
disassembly facility provided by `python -m pickletools <file.pickle>`,
for the above file this generates:

```
    0: \x80 PROTO      3
    2: ]    EMPTY_LIST
    3: q    BINPUT     0
    5: (    MARK
    6: ]        EMPTY_LIST
    7: q        BINPUT     1
    9: (        MARK
   10: X            BINUNICODE 'web1.cpu0.user'
   29: q            BINPUT     2
   31: ]            EMPTY_LIST
   32: q            BINPUT     3
   34: (            MARK
   35: J                BININT     1332444075
   40: G                BINFLOAT   10.5
   49: e                APPENDS    (MARK at 34)
   50: e            APPENDS    (MARK at 9)
   51: ]        EMPTY_LIST
   52: q        BINPUT     4
   54: (        MARK
   55: X            BINUNICODE 'web1.cpu1.user'
   74: q            BINPUT     5
   76: ]            EMPTY_LIST
   77: q            BINPUT     6
   79: (            MARK
   80: J                BININT     1332444076
   85: G                BINFLOAT   90.3
   94: e                APPENDS    (MARK at 79)
   95: e            APPENDS    (MARK at 54)
   96: e        APPENDS    (MARK at 5)
   97: .    STOP
highest protocol among opcodes = 2
```

Looking at the code and documentation it becomes evident that we are
dealing with a stack based virtual machine which keeps track of objects.
The file is just a list of serialized opcodes, the first one being
expected to be the protocol version and the last one a stop opcode. When
the stop opcode is met, the current object on the stack is popped.

In the case of graphite data the objects built are simple collections,
the relevant operations can be trimmed down to:

```
 2: ]    EMPTY_LIST
 6: ]        EMPTY_LIST
10: X            BINUNICODE 'web1.cpu0.user'
31: ]            EMPTY_LIST
35: J                BININT     1332444075
40: G                BINFLOAT   10.5
49: e                APPENDS
50: e            APPENDS
51: ]        EMPTY_LIST
55: X            BINUNICODE 'web1.cpu1.user'
76: ]            EMPTY_LIST
80: J                BININT     1332444076
85: G                BINFLOAT   90.3
94: e                APPENDS
95: e            APPENDS
96: e        APPENDS
```

### Parsing opcodes

<style>.hex1 { color: #859900; }
       .hex2 { color: #b58900; }
       .hex3 { color: #268bd2; }
</style>

In terms of layout on disk, opcodes are either fixed size or contain a
fixed size field indicating the size of the variable part.

####  Protocol opcode

The protocol opcode has a code of `0x80` and is followed by a single  byte for the version.

<pre>
    00000000: <span class="hex1">8003</span> 5d71 0028 5d71 0128 580e 0000 0077  <span class="hex1">..</span>]q.(]q.(X....w
    00000010: 6562 312e 6370 7530 2e75 7365 7271 025d  eb1.cpu0.userq.]
    00000020: 7103 284a ab7b 6b4f 4740 2500 0000 0000  q.(J.{kOG@%.....
    00000030: 0065 655d 7104 2858 0e00 0000 7765 6231  .ee]q.(X....web1
    00000040: 2e63 7075 312e 7573 6572 7105 5d71 0628  .cpu1.userq.]q.(
    00000050: 4aac 7b6b 4f47 4056 9333 3333 3333 6565  J.{kOG@V.33333ee
    00000060: 652e                                     e.
</pre>

#### Stop opcode

The stop opcode (`0x2e` or `.` in ASCII) denotes the end of the pickle data.

<pre>
    00000000: 8003 5d71 0028 5d71 0128 580e 0000 0077  ..]q.(]q.(X....w
    00000010: 6562 312e 6370 7530 2e75 7365 7271 025d  eb1.cpu0.userq.]
    00000020: 7103 284a ab7b 6b4f 4740 2500 0000 0000  q.(J.{kOG@%.....
    00000030: 0065 655d 7104 2858 0e00 0000 7765 6231  .ee]q.(X....web1
    00000040: 2e63 7075 312e 7573 6572 7105 5d71 0628  .cpu1.userq.]q.(
    00000050: 4aac 7b6b 4f47 4056 9333 3333 3333 6565  J.{kOG@V.33333ee
    00000060: 65<span class="hex1">2e</span>           e<span class="hex1">.</span>
</pre>

#### Empty list opcode

The empty list opcode has code `0x5d` (the ASCII equivalent of `]`), this opcode has no additional data.

<pre>
    00000000: 8003 <span class="hex1">5d</span>71 0028 <span class="hex1">5d</span>71 0128 580e 0000 0077  ..<span class="hex1">]</span>q.(<span class="hex1">]</span>q.(X....w
    00000010: 6562 312e 6370 7530 2e75 7365 7271 02<span class="hex1">5d</span>  eb1.cpu0.userq.<span class="hex1">]</span>
    00000020: 7103 284a ab7b 6b4f 4740 2500 0000 0000  q.(J.{kOG@%.....
    00000030: 0065 65<span class="hex1">5d</span> 7104 2858 0e00 0000 7765 6231  .ee]q.(X....web1
    00000040: 2e63 7075 312e 7573 6572 7105 <span class="hex1">5d</span>71 0628  .cpu1.userq.<span class="hex1">]</span>q.(
    00000050: 4aac 7b6b 4f47 4056 9333 3333 3333 6565  J.{kOG@V.33333ee
    00000060: 652e                                     e.
</pre>

#### Append opcode

The append opcode denotes the end of a list, the currently open
collection should be closed and pushed back onto the stack.

<pre>
    00000000: 8003 5d71 0028 5d71 0128 580e 0000 0077  ..]q.(]q.(X....w
    00000010: 6562 312e 6370 7530 2e75 7365> 7271 025d eb1.cpu0.userq.]
    00000020: 7103 284a ab7b 6b4f 4740 2500 0000 0000  q.(J.{kOG@%.....
    00000030: 00<span class="hex1">65 65</span>5d 7104 2858 0e00 0000 7765 6231  .<span class="hex1">ee</span>]q.(X....web1
    00000040: 2e63 7075 312e 7573 6572 7105 5d71 0628  .cpu1.userq.]q.(
    00000050: 4aac 7b6b 4f47 4056 9333 3333 3333 <span class="hex1">6565</span>  J.{kOG@V.33333<span class="hex1">ee</span>
    00000060: <span class="hex1">65</span>2e                                     <span class="hex1">e</span>.
</pre>

#### Unicode opcode

The unicode opcode has code `0x58` (or ASCII `X`) and follows a simple structure:

``` c
    struct bin_unicode {
       char       code;    /* 0x58 */
       u_int32_t  length;  /* payload size in network byte order */
       char      *payload; /* variable size */
    };
```

Here are the three fields highlighted from our example payloads each  time they appear:

<pre>
    00000000: 8003 5d71 0028 5d71 0128 <span class="hex1">58</span><span class="hex2">0e 0000 00</span><span class="hex3">77</span> ..]q.(]q.(<span class="hex1">X</span><span class="hex2">....</span><span class="hex3">w</span>
    00000010: <span class="hex3">6562 312e 6370 7530 2e75 7365 72</span>71 025d  <span class="hex3">eb1.cpu0.user</span>q.]
    00000020: 7103 284a ab7b 6b4f 4740 2500 0000 0000  q.(J.{kOG@%.....
    00000030: 0065 655d 7104 28<span class="hex1">58</span> <span class="hex2">0e00 0000</span> <span class="hex3">7765 6231</span>  .ee]q.(<span class="hex1">X</span><span class="hex2">....</span><span class="hex3">web1</span>
    00000040: <span class="hex3">2e63 7075 312e 7573 6572</span> 7105 5d71 0628  <span class="hex3">.cpu1.user</span>q.]q.(
    00000050: 4aac 7b6b 4f47 4056 9333 3333 3333 6565  J.{kOG@V.33333ee
    00000060: 652e                                     e.
</pre>

####  Integer opcode

Integers are stored with the `0x4a` opcode at a fixed length of 4
bytes and in network byte order.

<pre>
    00000000: 8003 5d71 0028 5d71 0128 580e 0000 0077 ..]q.(]q.(X....w
    00000010: 6562 312e 6370 7530 2e75 7365 7271 025d  eb1.cpu0.userq.]
    00000020: 7103 28<span class="hex1">4a</span> <span class="hex2">ab7b 6b4f</span> 4740 2500 0000 0000  q.(<span class="hex1">J</span><span class="hex2">.{kO</span>G@%.....
    00000030: 0065 655d 7104 2858 0e00 0000 7765 6231  .ee]q.(X....web1
    00000040: 2e63 7075 312e 7573 6572 7105 5d71 0628  .cpu1.userq.]q.(
    00000050: <span class="hex1">4a</span><span class="hex2">ac 7b6b 4f</span>47 4056 9333 3333 3333 6565  <span class="hex1">J</span><span class="hex2">.{kO</span>G@V.33333ee
    00000060: 652e                                     e.
</pre>

#### The infamous double opcode

The way doubles are serialized is a bit startling, it comes down to
just writing out the double storage. In C deserializing would come down to:

``` c
    double deserialize(const char *input) {
      double output;

      memcpy(&output, input, sizeof(output));
      return (output);
    }
```

<pre>
    00000000: 8003 5d71 0028 5d71 0128 580e 0000 0077  ..]q.(]q.(X....w
    00000010: 6562 312e 6370 7530 2e75 7365 7271 025d  eb1.cpu0.userq.]
    00000020: 7103 284a ab7b 6b4f <span class="hex1">47</span><span class="hex2">40 2500 0000 0000</span>  q.(J.{kO<span class="hex1">G</span><span class="hex2">@%.....</span>
    00000030: <span class="hex2">00</span>65 655d 7104 2858 0e00 0000 7765 6231  .ee]q.(X....web1
    00000040: 2e63 7075 312e 7573 6572 7105 5d71 0628  .cpu1.userq.]q.(
    00000050: 4aac 7b6b 4f<span class="hex1">47</span> <span class="hex2">4056 9333 3333 3333</span> 6565  J.{kO<span class="hex1">G</span><span class="hex2">@V.33333</span>ee
    00000060: 652e                                     e.
</pre>

### AST Generation

The Abstract Syntax Tree for such a format is nothing more than a list
of opcode. Parsing just requires making sure the first opcode is a
protocol one and should stop when a stop opcode is met.

Generating an AST for the above syntax in clojure turned out to be very
simple, provided we worked with the java
[ByteBuffer](http://docs.oracle.com/javase/7/docs/api/java/nio/ByteBuffer.html)
class.

Here's the bulk of the work:

``` clojure
(defn raw->ast
  "Convert binary data into a list of pickle opcodes and data"
  [bb]
  (lazy-seq
   (when (pos? (.remaining bb))
     (let [b    (bit-and 0xff (.get bb))
           elem (opcode b bb)]
       (cons elem (raw->ast bb))))))
```

A seq is built by fetching a byte and sending it to an `opcode`
function, along with the rest of the buffer.

The `opcode` function is best built as a `multimethod` which dispatches
on the opcode: `(defmulti opcode (fn [b _] (bit-or b 0x00)))`, methods
can then be implemented simply, for instance here are append and int
parsing:

``` clojure
(defmethod opcode 0x4a
  [_ bb]
  {:type :int :val (.getInt bb)})

(defmethod opcode 0x65
  [_ bb]
  {:type :append})
```

With this, we end up with an AST of the following form. It's now much
easier to write functions that parse this AST and extract

``` clojure
({:type :protocol, :version 3}
 {:type :startlist}
 {:type :binput, :index 0}
 {:type :mark}
 {:type :startlist}
 {:type :binput, :index 1}
 {:type :mark}
 {:type :unicode, :size 14, :val "web1.cpu0.user"}
 {:type :binput, :index 2}
 {:type :startlist}
 {:type :binput, :index 3}
 {:type :mark}
 {:type :int, :val 1332444075}
 {:type :double, :val 10.5}
 {:type :append}
 {:type :append}
 {:type :startlist}
 {:type :binput, :index 4}
 {:type :mark}
 {:type :unicode, :size 14, :val "web1.cpu1.user"}
 {:type :binput, :index 5}
 {:type :startlist}
 {:type :binput, :index 6}
 {:type :mark}
 {:type :int, :val 1332444076}
 {:type :double, :val 90.3}
 {:type :append}
 {:type :append}
 {:type :append}
 {:type :stop})
```

### Some final thoughts on pickle

I still think pickle should be avoided in general, but I found myself in
one of the rare cases where it's necessary to interact with it from
outside python. If you're a python developer and following along, please
consider other serialization formats.

Hopefully This should give you enough to start playing around with
pickle, here are a few resources for doing so in other languages:

-   In haskell: <https://hackage.haskell.org/package/python-pickle>
-   Pyrolite in Java: <https://pythonhosted.org/Pyro4/pyrolite.html>

