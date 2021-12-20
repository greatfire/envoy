#ifndef NATIVE_NODE_DRILL_COMMON_H_
#define NATIVE_NODE_DRILL_COMMON_H_

#include <iostream>
#include <memory>
#include <string>
#include <vector>

#include "base/rand_util.h"

#include "net/base/ip_address.h"
#include "net/traffic_annotation/network_traffic_annotation.h"
#include "net/url_request/url_request_context_getter.h"

#include "net/base/address_list.h"
#include "net/base/load_flags.h"
#include "net/cert/cert_verifier.h"
#include "net/cert/cert_verify_result.h"
#include "net/cert/ct_policy_enforcer.h"
#include "net/cert/do_nothing_ct_verifier.h"
#include "net/dns/host_resolver.h"
#include "net/http/transport_security_state.h"
#include "net/log/net_log_source.h"
#include "net/ssl/ssl_config_service.h"
#include "net/ssl/ssl_info.h"
#include "net/url_request/url_fetcher_delegate.h"

#include "base/single_thread_task_runner.h"

#ifdef XPATCH_CLI
#define MYLOG(...) fprintf(stderr, __VA_ARGS__)
#else
#ifdef OS_ANDROID
#include <android/log.h>
#include <jni.h>
#endif
#define MYLOG( \
    ...)  // __android_log_print(ANDROID_LOG_DEBUG, "xPatch", __VA_ARGS__)
#endif

#define ARRAY_SIZE(arr) (sizeof(arr) / sizeof((arr)[0]))

#define MSG_TYPE_PROXY_INFO 0x0001
#define MSG_TYPE_SHOW_TOAST 0x0002
#define MSG_TYPE_START_PAGE 0x0003
#define MSG_TYPE_COMMAND 0x0004

#define MAX_VALID_NODES 3

#define CLIENT_ID "0123456789012345678901234567890123"

#define AEAD_KEY \
  "0123456789012345678901234567890123456789012345678901234567890123"
#define AEAD_NONCE \

typedef void (*message_callback_)(int message_type,
                                  int message_payload_int,
                                  const std::string& message_payload_str);

extern net::NetworkTrafficAnnotationTag g_nt_net_traffic_annotation_tag;
extern const char* g_user_agent;
extern const uint16_t g_ssl_ciphers[48];

extern const char* g_domain_prefixes[28];
extern const char* g_domain_slds[22];
extern const char* g_domain_tlds[11];

extern const char* g_backend_filename;

#define DOMAIN_PREFIX_SIZE ARRAY_SIZE(g_domain_prefixes)
#define DOMAIN_SLD_SIZE ARRAY_SIZE(g_domain_slds)
#define DOMAIN_TLD_SIZE ARRAY_SIZE(g_domain_tlds)

#define RED "\x1B[31m"
#define GRN "\x1B[32m"
#define YEL "\x1B[33m"
#define COLOR_RESET "\x1B[0m"

enum State {
  STATE_TCP_CONNECT,
  STATE_SSL_CONNECT,
  STATE_NT_CONNECT,
  STATE_NT_DONE,
  STATE_NONE,
};

void SSLCipherSuiteToString(uint16_t cipher_suite,
                            std::string& cipher_suite_str);

class HostIpAddress {
 public:
  HostIpAddress(const std::string& host, const net::IPAddress& ip_address)
      : host_(host), ip_address_(ip_address) {}

  const std::string& getHost() { return host_; }
  const net::IPAddress& getIPAddress() { return ip_address_; }

 private:
  std::string host_;
  net::IPAddress ip_address_;
};

class CIDR {
 public:
  explicit CIDR(std::string cidr) {
    size_t prefix_length_in_bits = 0;
    net::IPAddress ip_address;
    if (ParseCIDRBlock(cidr, &ip_address, &prefix_length_in_bits)) {
      // https://stackoverflow.com/questions/2648764/whats-bad-about-shifting-a-32-bit-variable-32-bits
      if (prefix_length_in_bits < ip_address.size() * 8) {
        mask_ = 0xffffffff >> prefix_length_in_bits;
      } else if (prefix_length_in_bits == ip_address.size() * 8) {
        mask_ = 0;
      }
    } else {
      // MYLOG("invalid cidr %s\n", cidr.c_str());
      if (ip_address.AssignFromIPLiteral(cidr)) {
        mask_ = 0;
      } else {
        MYLOG("invalid raw ip_address: %s\n", cidr.c_str());
        mask_ = -1;
      }
    }

    const uint8_t* ptr = ip_address.bytes().data();
    b0_ = *ptr++, b1_ = *ptr++, b2_ = *ptr++, b3_ = *ptr++;
    // MYLOG("cidr=%s, ip_address=%s, mask_=%d\n", cidr.c_str(),
    //      ip_address.ToString().c_str(), mask_);
  }

  int getIPSize() { return mask_ + 1; }

  net::IPAddress getRandAddr(void) {
    if (mask_ > 0) {
      int random_mask = base::RandInt(0, mask_);
      uint8_t b0 = b0_ | (random_mask & 0xff000000) >> 24;
      uint8_t b1 = b1_ | (random_mask & 0x00ff0000) >> 16;
      uint8_t b2 = b2_ | (random_mask & 0x0000ff00) >> 8;
      uint8_t b3 = b3_ | (random_mask & 0x000000ff) >> 0;
      return net::IPAddress(b0, b1, b2, b3);
    }
    // TODO mask_ = -1: invalid ip
    return net::IPAddress(b0_, b1_, b2_, b3_);
  }

 private:
  uint8_t b0_, b1_, b2_, b3_;
  int mask_;
};

class NTURLRequestContextGetter : public net::URLRequestContextGetter {
 public:
  NTURLRequestContextGetter(
      const scoped_refptr<base::SingleThreadTaskRunner>& network_task_runner,
      net::URLRequestContext* context);
  net::URLRequestContext* GetURLRequestContext() override;
  scoped_refptr<base::SingleThreadTaskRunner> GetNetworkTaskRunner()
      const override;

 protected:
  ~NTURLRequestContextGetter() override;

 private:
  const scoped_refptr<base::SingleThreadTaskRunner> network_task_runner_;
  net::URLRequestContext* context_;
};

class NTSSLConfigService : public net::SSLConfigService {
 public:
  explicit NTSSLConfigService(net::SSLContextConfig context_config)
      : context_config_(context_config) {
    // TODO random cipher suite
  }
  ~NTSSLConfigService() override = default;

  net::SSLContextConfig GetSSLContextConfig() override;
  bool ShouldSuppressLegacyTLSWarning(
      const std::string& hostname) const override;

  bool CanShareConnectionWithClientCerts(
      const std::string& hostname) const override;

 private:
  net::SSLContextConfig context_config_;
};

class NTCertVerifier : public net::CertVerifier {
 public:
  NTCertVerifier() : default_result_(net::OK) {}
  ~NTCertVerifier() override {}

  int Verify(const RequestParams& params,
             net::CertVerifyResult* verify_result,
             net::CompletionOnceCallback callback,
             std::unique_ptr<Request>* out_req,
             const net::NetLogWithSource& net_log) override;

  void SetConfig(const Config& config) override;
  void set_default_result(int default_result) {
    default_result_ = default_result;
  }

 private:
  int default_result_;
  Config config_;
  // const CertificateList empty_cert_list_;
  // scoped_refptr<CertVerifyProc> verify_proc_;
};


// fuchsia/base/test_devtools_list_fetcher.cc
class NTURLFetcherDelegate : public net::URLFetcherDelegate {
 public:
  explicit NTURLFetcherDelegate(const base::Closure& quit_loop_func);
  ~NTURLFetcherDelegate() override;

  void OnURLFetchComplete(const net::URLFetcher* source) override;

  base::Closure quit_loop_func_;
};

class NTCTPolicyEnforcer : public net::CTPolicyEnforcer {
 public:
  NTCTPolicyEnforcer() = default;
  ~NTCTPolicyEnforcer() override = default;
  net::ct::CTPolicyCompliance CheckCompliance(
      net::X509Certificate* cert,
      const net::ct::SCTList& verified_scts,
      const net::NetLogWithSource& net_log) override;
};
#endif  // NATIVE_NODE_DRILL_COMMON_H_
