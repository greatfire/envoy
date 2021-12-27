#ifndef NATIVE_NODE_DRILL_XPATCH_H_
#define NATIVE_NODE_DRILL_XPATCH_H_
#include <openssl/base.h>
#include <openssl/err.h>
#include <openssl/pkcs7.h>
#include <openssl/ssl.h>
#include <openssl/x509.h>
#include <openssl/x509v3.h>
#include <pthread.h>

#include <memory>
#include <string>
#include <vector>

// TODO commented out
//#include "base/message_loop/message_loop.h"
#include "base/timer/timer.h"

class XBackend;

class XPatch {
 public:
  explicit XPatch(int nt_threshold,
                  const std::string& language,
                  const std::string& data_dir,
                  const std::string& xbackend_path,
                  const std::string& pattern);
  ~XPatch();

  void setSSLParam(SSL_CTX* ctx);
  // const std::string& getSNIHost();
  void testBackends();
  void setMessageCallBack(message_callback_ message_cb) {
    message_cb_ = message_cb;
  }
  void pushMessage(int message_type,
                   int message_payload_int,
                   const std::string& message_payload_str);
  int getNTThreshold() { return nt_threshold_; }
  std::string& getDataDir() { return data_dir_; }
  std::string& getLanguage() { return language_; }
  std::string& getStartupPage() { return startup_page_; }
  bool isBadIpAddress(std::string& ip_address) {
    return bad_ip_address_.compare(ip_address) == 0;
  }
  void gitClone();
  bool doGitClone(std::string& uri, std::string& git_repo_path);

 private:
  void checkApkFingerprint(std::string& apk_file);
  void randomizeSNIHost();
  void randomizeCiphers();
  void initBadIpAddress();
  void loadBackends(const std::string& extra_xbackend_path,
                    const std::string& include_pattern);
  void loadCACerts();

  std::string sni_host_;
  uint16_t disabled_cipher_suite_;
  std::string language_;
  std::string data_dir_;
  std::string apk_file_;
  std::string startup_page_;
  std::string bad_ip_address_;
  std::vector<X509*> ca_certs_;
  int nt_threshold_;
  bool is_fingerprint_matched_;
  std::string xbackend_data_;
  std::vector<XBackend*> xbackends_;
  message_callback_ message_cb_ = NULL;
  // TODO commented out
  //std::unique_ptr<base::MessageLoop> message_loop_;
  // std::unique_ptr<net::URLRequestContext> context_;
  base::RepeatingTimer timer_;
  base::Closure quit_loop_func_;
};
#endif  // NATIVE_NODE_DRILL_XPATCH_H_
