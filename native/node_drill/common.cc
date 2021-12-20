#include "common.h"

#include <iostream>
#include <memory>
#include <string>

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
#include "net/ssl/ssl_cipher_suite_names.h"
#include "net/ssl/ssl_config_service.h"
#include "net/ssl/ssl_info.h"
#include "net/url_request/url_fetcher_delegate.h"

#include "base/single_thread_task_runner.h"

net::NetworkTrafficAnnotationTag g_nt_net_traffic_annotation_tag =
    net::DefineNetworkTrafficAnnotation("nt", R"(
      semantics {
        sender: "NT"
        description:
          "node test"
        trigger:
          "On Startup. "
        data: "no data, just connectivity test"
        destination: WEBSITE
      }
      policy {
        cookies_allowed: NO
        setting: "This feature cannot be disabled in settings."
        policy_exception_justification:
          "Not implemented, considered not useful."
      })");

const char* g_user_agent =
    "Mozilla/5.0 (Linux; Android 9.0.0) AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/76.0.3626.56 Mobile Safari/537.36";

const uint16_t g_ssl_ciphers[] = {
    0x5a5a, 0x1301, 0x1302, 0x1303, 0xc02b, 0xc02f, 0xc02c, 0xc030,
    0xcca9, 0xcca8, 0x0004, 0xc013, 0xc014, 0x000a, 0x000b, 0x1305,
    0xcca8, 0xc02a, 0xc02d, 0xc009, 0xc031, 0xcca0, 0xcc2d, 0xcc3e,
    0xc007, 0xc009, 0xc00a, 0xc011, 0xc013, 0xc014, 0xc02b, 0xc02f,
    0xc009, 0xc001, 0xc001, 0xc009, 0x000e, 0xc020, 0xc021, 0xc026,
    0xcca8, 0xcca9, 0x009c, 0x009d, 0x009e, 0x009a, 0x002f, 0x0035};

const char* g_domain_prefixes[] = {
    "imgs", "dl",   "www",     "docs",  "mail", "cdn",   "ftp",
    "my",   "wiki", "account", "store", "shop", "pay",   "buy",
    "log",  "jira", "email",   "sql",   "db",   "ssl",   "smile",
    "img",  "cn",   "login",   "get",   "push", "cache", "mm"};
// sld: second level domain
const char* g_domain_slds[] = {
    "apple",      "ubuntu",        "paypal",  "ebay",     "amazon",
    "intel",      "images-amazon", "hotmail", "bestbuy",  "microsoft",
    "linode",     "digitialocean", "sandisk", "neweggs",  "akamiedia",
    "sonymobile", "alibaba",       "apache",  "sina-cdn", "people",
    "xiaomi",     "foxmail"};
// tld: top level domain
const char* g_domain_tlds[] = {"com", "com", "com", "org", "com", "net",
                               "com", "com", "com", "net", "com"};

const char* g_backend_filename = "xbackend.ng";

void SSLCipherSuiteToString(uint16_t cipher_suite,
                            std::string& cipher_suite_str) {
  const char *key_exchange_str = NULL, *cipher_str = NULL, *mac_str = NULL;
  bool is_aead, is_tls13;
  // ???-???-???
  net::SSLCipherSuiteToStrings(&key_exchange_str, &cipher_str, &mac_str,
                               &is_aead, &is_tls13, cipher_suite);
  if (key_exchange_str) {
    cipher_suite_str.append(key_exchange_str);
    cipher_suite_str.append("-");
  }
  if (cipher_str) {
    cipher_suite_str.append(cipher_str);
  }
  if (mac_str) {
    cipher_suite_str.append("-");
    cipher_suite_str.append(mac_str);
  }
}

NTURLRequestContextGetter::NTURLRequestContextGetter(
    const scoped_refptr<base::SingleThreadTaskRunner>& network_task_runner,
    net::URLRequestContext* context)
    : network_task_runner_(network_task_runner), context_(context) {}
NTURLRequestContextGetter::~NTURLRequestContextGetter() {}

net::URLRequestContext* NTURLRequestContextGetter::GetURLRequestContext() {
  return context_;
}
scoped_refptr<base::SingleThreadTaskRunner>
NTURLRequestContextGetter::GetNetworkTaskRunner() const {
  return network_task_runner_;
}

net::SSLContextConfig NTSSLConfigService::GetSSLContextConfig() {
  return context_config_;
}
bool NTSSLConfigService::ShouldSuppressLegacyTLSWarning(
    const std::string& hostname) const {
  return false;
}

bool NTSSLConfigService::CanShareConnectionWithClientCerts(
    const std::string& hostname) const {
  return false;
}

int NTCertVerifier::Verify(const RequestParams& params,
                           net::CertVerifyResult* verify_result,
                           net::CompletionOnceCallback callback,
                           std::unique_ptr<Request>* out_req,
                           const net::NetLogWithSource& net_log) {
  verify_result->verified_cert = params.certificate();
  verify_result->is_issued_by_known_root = true;
  net::HashValue hash(net::HASH_VALUE_SHA256);
  memset(hash.data(), 0, hash.size());
  verify_result->public_key_hashes.push_back(hash);
  return net::OK;
}

void NTCertVerifier::SetConfig(const Config& config) {
  config_ = config;
}

NTURLFetcherDelegate::NTURLFetcherDelegate(const base::Closure& quit_loop_func)
    : quit_loop_func_(quit_loop_func) {}
NTURLFetcherDelegate::~NTURLFetcherDelegate() = default;

void NTURLFetcherDelegate::OnURLFetchComplete(const net::URLFetcher* source) {
  quit_loop_func_.Run();
}

net::ct::CTPolicyCompliance NTCTPolicyEnforcer::CheckCompliance(
    net::X509Certificate* cert,
    const net::ct::SCTList& verified_scts,
    const net::NetLogWithSource& net_log) {
  return net::ct::CTPolicyCompliance::CT_POLICY_COMPLIES_VIA_SCTS;
}
