#ifndef NATIVE_NODE_DRILL_XNODE_H_
#define NATIVE_NODE_DRILL_XNODE_H_

#include "common.h"

#include <memory>
#include <string>

// TODO COMMENT OUT
// #include "base/message_loop/message_loop.h"
#include "base/timer/timer.h"
#include "net/base/ip_address.h"
#include "net/socket/client_socket_factory.h"
#include "net/socket/ssl_client_socket.h"
#include "net/socket/stream_socket.h"

#include "net/base/address_list.h"
#include "net/base/load_flags.h"
#include "net/cert/cert_verifier.h"
#include "net/cert/ct_policy_enforcer.h"
#include "net/cert/do_nothing_ct_verifier.h"
#include "net/dns/host_resolver.h"
#include "net/http/transport_security_state.h"
#include "net/log/net_log_source.h"
#include "net/ssl/ssl_info.h"
#include "net/traffic_annotation/network_traffic_annotation.h"

#define SAN_WILDCARD '*'

class XNode {
 public:
  XNode(const std::string& sni_host,
        uint16_t disabled_cipher_suite,
        const std::string& host,
        const std::string& base_url);
  ~XNode();

  int doCrTest(const net::IPAddress& ip_address);

  void OnCrTestTimeout(const net::IPAddress& ip_address);

  int doCrSSLConnect();

  int doCrHTTPRequest(const std::string& sni_host,
                      const net::IPAddress& ip_address,
                      const std::string& path);

  void OnIOComplete(int result);

  const std::string& getSNIHost();

  const std::string& getResponse();

 private:
  std::string sni_host_;
  // Subject Alternative Name
  std::vector<std::string> cert_sans_;
  std::string cert_san_;
  std::string ip_address_;
  uint16_t disabled_cipher_suite_;
  std::string host_;
  std::string base_url_;
  base::Closure quit_loop_func_;
  base::OneShotTimer timer_;
  std::unique_ptr<net::StreamSocket> transport_;
  std::unique_ptr<net::SSLClientSocket> ssl_sock_;
  net::ClientSocketFactory* socket_factory_;
  net::SSLConfig ssl_config_;
  net::SSLContextConfig ssl_context_config_;
  State next_state_;
  std::string resp_;
  int result_;
};
#endif  // NATIVE_NODE_DRILL_XNODE_H_
