#
# Be sure to run `pod lib lint GreatfireEnvoy.podspec' to ensure this is a
# valid spec before submitting.
#
# Any lines starting with a # are optional, but their use is encouraged
# To learn more about a Podspec see https://guides.cocoapods.org/syntax/podspec.html
#

Pod::Spec.new do |s|
  s.name             = 'GreatfireEnvoy'
  s.version          = '0.2.0'
  s.summary          = "Greatfire's Envoy is a manager for various proxy implementations."

  s.description      = <<-DESC
  It will automatically find the best working proxy and provide the necessary configuration
  via helper methods.

  Supported Proxies are:
  - Envoy HTTP proxy (partial support on Apple platforms)
  - V2Ray
  - Hysteria2
  - Pluggable Transports together with an Envoy HTTP proxy or a SOCKS5 proxy:
    - Meek
    - Obfs4
    - WebTunnel
    - Snowflake
                       DESC

  s.homepage         = 'https://github.com/greatfire/envoy'
  s.license          = { :type => 'Apache-2.0', :file => 'LICENSE' }
  s.author           = { 'Benjamin Erhart' => 'berhart@netzarchitekten.com' }
  s.source           = { :git => 'https://github.com/greatfire/envoy.git', :tag => "apple-#{s.version}" }
  s.social_media_url = 'https://chaos.social/@tla'
  s.readme           = "https://raw.githubusercontent.com/greatfire/envoy/apple-#{s.version}/apple/README.md"
  s.changelog        = 'https://raw.githubusercontent.com/greatfire/envoy/apple-#{s.version}/apple/CHANGELOG.md'


  s.swift_versions = '5.0'

  s.ios.deployment_target = '13.0'
  s.osx.deployment_target = '11'

  s.static_framework = true

  s.test_spec 'Tests' do |t|
      t.source_files = 'apple/Tests/GreatfireEnvoyTests/**/*'
  end

  s.subspec 'Core' do |ss|
    ss.source_files = 'apple/Sources/GreatfireEnvoy/**/*'

    ss.dependency 'IEnvoyProxy', '~> 2.0'
  end

  s.subspec 'Curl' do |ss|
    ss.dependency 'GreatfireEnvoy/Core'
    ss.dependency 'SwiftyCurl', '~> 0.4'

    ss.test_spec 'Tests' do |t|
        t.source_files = 'apple/Tests/GreatfireEnvoyTests/**/*'
    end

    ss.pod_target_xcconfig = {
      'GCC_PREPROCESSOR_DEFINITIONS' => 'USE_CURL=1',
      'SWIFT_ACTIVE_COMPILATION_CONDITIONS' => '$(inherited) USE_CURL',
    }
  end

  s.default_subspec = 'Core'
end
