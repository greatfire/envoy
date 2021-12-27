#ifndef NATIVE_NODE_DRILL_XBACKEND_H_
#define NATIVE_NODE_DRILL_XBACKEND_H_
#include "common.h"

#include <iostream>
#include <string>
#include <vector>

#include "sql/database.h"

#define HOST_TYPE_DEPRECATED 'A'
#define HOST_TYPE_B 'B'

extern pthread_mutex_t g_backends_mutex;
extern size_t g_node_size;
extern pthread_mutex_t g_backend_update_mutex;

class XPatch;

class XBackend {
 public:
  XBackend(const std::string& xbackend_line,
           const std::string& sni_host,
           uint16_t disabled_cipher_suite,
           int nt_threshold_,
           const std::string& data_dir);
  ~XBackend();

  const std::string& getThreadName();
  const std::string& getHost();
  char getType();
  int getProgress();
  // int getNodeCount() { return host_ip_addresses_.size(); }
  void setMessageCallBack(message_callback_ message_cb) {
    message_cb_ = message_cb;
  }
  bool doBackendTest();
  int doHTTPGet(const std::string& path, std::string& resp);
  void pushMessage(int message_type,
                   int message_payload_int,
                   const std::string& message_payload_str);
  // HostIpAddress* firstHostIpAddress();
  void updateConfig();

 private:
  void loadNodes();
  void saveNode(const std::string& ip_address,
                const std::string& sni_host,
                int msec);

  int priority_;
  char type_;
  std::string sni_host_;
  std::string host_;
  std::string host_md5_;  // caching
  std::string base_url_;
  // std::string resp_;
  std::vector<CIDR*> cidrs_;
  std::vector<HostIpAddress*> host_ip_addresses_;
// base::OneShotTimer timer_;
// std::unique_ptr<net::URLRequestContext> context_;
// SSLClientSocketContext ssl_context_;
  net::SSLContextConfig ssl_context_config_;
  uint16_t disabled_cipher_suite_;
  int nt_threshold_;
  std::string data_dir_;
  sql::Database db_;
  message_callback_ message_cb_ = NULL;
  // base::Closure quit_loop_func_;
  // std::unique_ptr<SSLClientSocket> ssl_sock_;
  // std::unique_ptr<StreamSocket> transport_;
  int progress_;
  // int nt_err_;     // NT ERROR from TCP or SSL connect
  // int nt_ok_err_;  // NT ERROR from v3/test check
  // int nt_ok_;      // NT OK
  std::string thread_name_;
};

bool shouldStopNT();
bool addBackend(XBackend* xbackend, net::IPAddress ip_address);

extern std::vector<XBackend*> g_backends;
extern size_t g_backend_size;
#endif  // NATIVE_NODE_DRILL_XBACKEND_H_
