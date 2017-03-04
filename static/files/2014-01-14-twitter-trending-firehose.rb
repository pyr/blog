require 'tweetstream'
require 'riemann/client'

TweetStream.configure do |config|
  config.consumer_key       = 'xxx'
  config.consumer_secret    = 'xxx'
  config.oauth_token        = 'xxx'
  config.oauth_token_secret = 'xxx'
  config.auth_method        = :oauth
end

riemann = Riemann::Client.new


TweetStream::Client.new.sample do |status|
  tags = status.text.scan(/\s#([[:alnum:]]+)/).map{|x| x.first.downcase}

  tags.each do |tag|
    puts "emitting #{tag}"
    riemann << {
      service: "#{tag}",
      metric: 1.0,
      tags: ["twitter"],
      ttl: 3600
    }
  end
end
