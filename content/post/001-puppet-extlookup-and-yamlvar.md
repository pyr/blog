+++
date = "2011-02-05T14:42:57+01:00"
title = "Puppet, extlookup, and yamlvar"
draft = false
+++

When you dive in a complex project, even though you try to be as good as
possible with documentation, sometime you just miss things.

I've had a good opportunity to realize this just recently while working
on puppet. A common head scratcher in puppet is finding a way to keep
system, technology and platform specifics separate. I will probably
write at length on this particular subject a bit later, but to give a
quick explanation, just consider this quick scenario:

- You host web servers for the same vhosts in two different locations
- You manage them with the same puppet instance
- They need a unique configuration
- Some details such as their name server change

Puppet allows you to get to the point where you can just write this:

```puppet
class webserver { 
    include unix
    include nginx

    nginx::upstream { rails_app:
        servers => ["127.0.0.1:8000", "127.0.0.1:8001"]
    }
    nginx::upstream_vhost { "www.example.com":
        upstream    => rails_app,
        listen      => 80
    }
    nginx::static_vhost { "static.example.com":
        root    => "/srv/www/static.example.com",
        listen  => 80
    }
}
```

This is great, generic and allows you to deploy configurations to many
machines, so it's a bit of a hassle to have to go through the trouble of
going through case statements just for DNS.

```ruby
module Puppet::Parser::Functions
    require 'yaml'

    newfunction(:yamlvar, :type => :rvalue) do |args|
        defval = args[1]
        yaml_path = args[2] || lookupvar('yamlvar_data')

        unless yaml_path or (yaml_path = lookupvar('yamlvar_data'))
            raise Puppet::ParseError, "No configuration for yamlvar"
        end 

        begin
            data = YAML.load_file(yaml_path)
        rescue
            raise Puppet::ParseError, "Cannot read yaml data"
        end 

        unless data['order']
            data['order'] = %w(fqdn operatingsystem)
        end 

        # parse preference tab in this context
        prefs = data['order'].map{|x| lookupvar(x)}.select{|x| x}
        prefs << 'default'

        val = data['values'][args[0]] or args[1]
        unless val 
            raise Puppet::ParseError, "Cannot find #{args[0]}"
        end 

        # map strings to hashes
        if val.is_a? Hash
            # find the union of keys in our order tab and keys available
            key = (prefs & val.keys).first

            # this cannot happen
            raise Puppet::ParseError, "Key is nil ?" unless key 
        
            val = val[key]
        end 

        raise Puppet::ParseError, "Cannot find val for #{args[0]}" unless val 
        val 
    end
end
```

This allows me do do clever local variables like these:

```yaml
---
order:  [ fqdn, operatingsystem ]
values:
    ns_server:
        www01.remote.example.com: "10.1.1.1"
        default: "10.1.2.1"
```



And call them from classes like this:

```puppet
class unix {
    [...]
    $ns_server = yamlvar(ns_server)
    file { "/etc/resolv.conf":
        owner => root,
        group => $root_group,
        mode => 0644,
        content => template("unix/resolv.conf.erb")
    }
    [...]
}
```

Well this is all fine and dandy until I came upon this:
[complex data and puppet](http://www.devco.net/archives/2009/08/31/complex_data_and_puppet.php][complex data and puppet).
That's right, @ripienaar already did the work and guess what?
[puppet 2.6.1 and extlookup](http://www.devco.net/archives/2010/09/14/puppet_261_and_extlookup.php) it's already in puppet 2.6.1!
