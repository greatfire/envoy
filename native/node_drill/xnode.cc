#include "xnode.h"

#include <memory>
#include <utility>
#include <vector>

#include "base/strings/stringprintf.h"

#include "net/socket/tcp_client_socket.h"
#include "net/url_request/url_fetcher.h"
#include "net/url_request/url_request_context.h"
#include "net/url_request/url_request_context_builder.h"

#include "net/base/address_list.h"
#include "net/base/load_flags.h"
#include "net/cert/cert_verifier.h"
#include "net/cert/ct_policy_enforcer.h"
#include "net/cert/do_nothing_ct_verifier.h"
#include "net/dns/host_resolver.h"
#include "net/http/transport_security_state.h"
#include "net/log/net_log_source.h"
#include "net/socket/client_socket_handle.h"
#include "net/ssl/ssl_info.h"

#include "net/proxy_resolution/proxy_config_service_fixed.h"

#include "base/threading/thread_task_runner_handle.h"
#include "base/run_loop.h"

// doCrTest -> doCrSSLConnect -> doCrHTTPRequest
// TODO sni_host_ will be set when get certificate alt names
XNode::XNode(const std::string& sni_host,
             uint16_t disabled_cipher_suite,
             const std::string& host,
             const std::string& base_url)
    : sni_host_(sni_host),
      disabled_cipher_suite_(disabled_cipher_suite),
      host_(host),
      base_url_(base_url) {
  socket_factory_ = net::ClientSocketFactory::GetDefaultFactory();
  next_state_ = STATE_NONE;
  result_ = net::OK;


// TODO reset disabled_cipher_suite
// std::vector<uint16_t> disabled_cipher_suites;
// disabled_cipher_suites.push_back(disabled_cipher_suite);
// xxx.disabled_cipher_suites = disabled_cipher_suites;
  ssl_context_config_.disabled_cipher_suites.push_back(disabled_cipher_suite_);
}

XNode::~XNode() {}

int XNode::doCrTest(const net::IPAddress& ip_address) {
  int msec = 0;
  int64_t connect_timeout = 3;

  base::RunLoop run_loop;
  quit_loop_func_ = run_loop.QuitClosure();
  next_state_ = STATE_TCP_CONNECT;
  resp_.assign(" ");
  uint16_t port = 443;
  transport_.reset(new net::TCPClientSocket(
      net::AddressList(net::IPEndPoint(ip_address, port)), nullptr, nullptr,
      net::NetLogSource()));
  transport_->Connect(
      base::BindOnce(&XNode::OnIOComplete, base::Unretained(this)));
  timer_.Start(
      FROM_HERE, base::TimeDelta::FromSeconds(connect_timeout),
      base::Bind(&XNode::OnCrTestTimeout, base::Unretained(this), ip_address));
  run_loop.Run();
  if (timer_.IsRunning()) {
    timer_.Stop();
  }

  if (result_ == net::OK) {
    net::SSLInfo ssl_info;
    ssl_sock_->GetSSLInfo(&ssl_info);
    net::X509Certificate* cert = ssl_info.unverified_cert.get();
    if (cert && false == cert->HasExpired()) {
      cert->GetSubjectAltName(&cert_sans_, nullptr);
      if (cert_sans_.size() > 0) {
        int cert_san_idx = base::RandInt(0, cert_sans_.size() - 1);
        cert_san_ = cert_sans_[cert_san_idx];
        if (!cert_san_.empty()) {
          net::IPEndPoint ip_end_point;
          ssl_sock_->GetPeerAddress(&ip_end_point);
          MYLOG(
              "%lu begin CrHTTPRequest %-19s cert_san_=%-30s, "
              "sni_host_=%s\n",
              pthread_self(), ip_end_point.ToString().c_str(),
              cert_san_.c_str(), sni_host_.c_str());
          // NOTE sni_host_ member is not used
          msec = doCrHTTPRequest(cert_san_, ip_end_point.address(), "v3/test");
        } else {
          MYLOG("no sni found for %s\n", ip_address.ToString().c_str());
        }
      } else {
        result_ = net::ERR_CERT_COMMON_NAME_INVALID;
      }
    } else {
      result_ = net::ERR_CERT_DATE_INVALID;
    }
  }
  return msec;
}

void XNode::OnCrTestTimeout(const net::IPAddress& ip_address) {
  if (next_state_ == STATE_TCP_CONNECT) {
    timer_.Stop();
    result_ = net::ERR_TIMED_OUT;
    quit_loop_func_.Run();
  }
}

int XNode::doCrSSLConnect() {
  uint16_t port = 443;
  net::HostPortPair host_port_pair(sni_host_, port);

  net::CertVerifier* cert_verifier = new NTCertVerifier();
  net::TransportSecurityState* transport_security_state =
      new net::TransportSecurityState;
  net::CTVerifier* cert_transparency_verifier = new net::DoNothingCTVerifier;
  net::CTPolicyEnforcer* ct_policy_enforcer = new NTCTPolicyEnforcer;
  std::unique_ptr<NTSSLConfigService> ssl_config_service(
      new NTSSLConfigService(ssl_context_config_));
  net::SSLClientContext* ssl_context = new net::SSLClientContext(
      ssl_config_service.get(), cert_verifier, transport_security_state,
      cert_transparency_verifier, ct_policy_enforcer,
      NULL /* ssl_client_session_cache */, NULL /* sct_auditing_delegate) */);
  ssl_sock_ = socket_factory_->CreateSSLClientSocket(
      ssl_context, std::move(transport_), host_port_pair, ssl_config_);

  next_state_ = STATE_SSL_CONNECT;
  int rv = ssl_sock_->Connect(
      base::BindOnce(&XNode::OnIOComplete, base::Unretained(this)));
  return rv;
}

int XNode::doCrHTTPRequest(const std::string& cert_san,
                           const net::IPAddress& ip_address,
                           const std::string& path) {
  std::string sni_host_used, url;
  // TODO should we reassign sni_host_ ?
  if (!cert_san.empty() && cert_san.at(0) == SAN_WILDCARD) {
    int domain_prefix_idx = base::RandInt(0, DOMAIN_PREFIX_SIZE - 1);
    sni_host_used =
        base::StringPrintf("%s%s", g_domain_prefixes[domain_prefix_idx],
                           cert_san.substr(1, cert_san.size() - 1).c_str());
  } else {
    sni_host_used.assign(cert_san);
  }
  url = base::StringPrintf("https://%s%s%s", sni_host_used.c_str(),
                           base_url_.c_str(), path.c_str());

  // network_delegate is moved
  base::RunLoop run_loop;
  NTURLFetcherDelegate delegate(run_loop.QuitClosure());

  net::URLRequestContextBuilder context_builder;
  context_builder.set_user_agent(g_user_agent);
  context_builder.DisableHttpCache();
  context_builder.set_proxy_config_service(
      std::make_unique<net::ProxyConfigServiceFixed>(
          net::ProxyConfigWithAnnotation::CreateDirect()));


  auto resolver = net::HostResolver::CreateStandaloneResolver(
      net::NetLog::Get(), net::HostResolver::ManagerOptions(),
      base::StringPrintf("MAP * %s", ip_address.ToString().c_str()), true);

  context_builder.set_host_resolver(std::move(resolver));
  std::unique_ptr<NTCertVerifier> cert_verifier(new NTCertVerifier());
  context_builder.SetCertVerifier(std::move(cert_verifier));
  std::unique_ptr<NTSSLConfigService> ssl_config_service(
      new NTSSLConfigService(ssl_context_config_));
  context_builder.set_ssl_config_service(std::move(ssl_config_service));
  std::unique_ptr<net::URLRequestContext> context = context_builder.Build();
  scoped_refptr<net::URLRequestContextGetter> request_context_getter(
      new NTURLRequestContextGetter(base::ThreadTaskRunnerHandle::Get(),
                                    context.get()));
  std::unique_ptr<net::URLFetcher> fetcher =
      net::URLFetcher::Create(GURL(url), net::URLFetcher::GET, &delegate,
                              g_nt_net_traffic_annotation_tag);
  fetcher->SetRequestContext(request_context_getter.get());
  fetcher->SetLoadFlags(net::LOAD_BYPASS_CACHE | net::LOAD_DISABLE_CACHE);
  fetcher->SetStopOnRedirect(true);
  fetcher->AddExtraRequestHeader("Client: " CLIENT_ID);
  std::string host_header("Host: ");
  host_header.append(host_);
  fetcher->AddExtraRequestHeader(host_header);
  base::TimeTicks ticks_start = base::TimeTicks::Now();
  fetcher->Start();
  run_loop.Run();

  base::TimeTicks ticks_stop = base::TimeTicks::Now();
  base::TimeDelta delta = ticks_stop - ticks_start;
  int status_code = fetcher->GetResponseCode(), nt_duration = 0;
  if (fetcher->GetResponseAsString(&resp_)) {
    if (status_code == 200) {
      nt_duration = static_cast<int>(delta.InMilliseconds());
    } else {
      resp_.assign(" ");
    }
  }
  MYLOG(GRN
        "%lu CrHTTPRequest %-30s in %4dms, ip=%-15s, "
        "(in)sni_host=%-22s, sni_host_=%-22s, cert_san_=%-22s, host=%-22s, "
        "status_code=%d, resp=%.20s\n" COLOR_RESET,
        pthread_self(), url.c_str(), nt_duration, ip_address.ToString().c_str(),
        sni_host_used.c_str(), sni_host_.c_str(), cert_san_.c_str(),
        host_.c_str(), status_code, resp_.c_str());
  return nt_duration;
}

void XNode::OnIOComplete(int result) {
  int rv = result;
  do {
    State state = next_state_;
    next_state_ = STATE_NONE;

    switch (state) {
      case STATE_TCP_CONNECT: {
        if (result == net::OK) {
          rv = doCrSSLConnect();
        } else {
          quit_loop_func_.Run();
        }
      } break;

      case STATE_SSL_CONNECT:
        quit_loop_func_.Run();
        break;

      default:
        break;
    }
  } while (rv != net::ERR_IO_PENDING && next_state_ != STATE_NONE);
  result_ = result;
}

const std::string& XNode::getSNIHost() {
  return sni_host_;
}

const std::string& XNode::getResponse() {
  return resp_;
}
