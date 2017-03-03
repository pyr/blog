#+title: A Leiningen plugin for Jenkins
#+date: 2012-07-18

![test all the things](http://i.imgur.com/ZaBcN.jpg)

I use jenkins extensively to test apps and clojure is my go-to language
for plenty of use cases now. [leiningen](http://leiningen.org) is a very
nice build tool that lets you get up and running very quickly, even for
java only projects. It relies on maven under the covers. Its only
drawback used to be the lack of support in jenkins which left you with
two choices:

-   Add a pre-build step that would get leiningen
-   Produce pom.xml files to build projects with maven

I'm happy to announce that there is now a third option,
[jenkins-leiningen](https://github.com/pyr/jenkins-leiningen) which
makes leiningen integration in jenkins much easier. You will only need
to push the leiningen standalone jar on your build machine and then
provide the necessary leiningen build steps in jenkins.
