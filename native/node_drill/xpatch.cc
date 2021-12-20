#include "cacert_pem.h"
#include "xbackend_data.h"

#include "common.h"
// #include "xnode.h"
#include "xbackend.h"
#include "xpatch.h"

#include <arpa/inet.h>
#include <fcntl.h>
#include <netdb.h>
#include <netinet/in.h>
#include <pthread.h>
#include <sys/select.h>
#include <sys/socket.h>
#include <unistd.h>
#include <algorithm>
#include <random>

#include <iostream>
#include <memory>
#include <utility>

#ifdef OS_ANDROID
#include "base/android/build_info.h"
#include "base/android/jni_android.h"
#include "base/android/jni_string.h"
#include "base/android/locale_utils.h"
#include "base/android/path_utils.h"
#endif

#include "base/at_exit.h"
#include "base/callback_helpers.h"
#include "base/command_line.h"
#include "base/files/file_path.h"
#include "base/files/file_util.h"
#include "base/logging.h"
#include "base/macros.h"
#include "base/memory/ptr_util.h"
#include "base/memory/ref_counted.h"
#include "base/memory/weak_ptr.h"
#include "base/numerics/safe_math.h"
#include "base/rand_util.h"
#include "base/run_loop.h"
#include "base/single_thread_task_runner.h"
#include "base/strings/pattern.h"
#include "base/strings/string_number_conversions.h"
#include "base/strings/string_split.h"
#include "base/strings/string_util.h"
#include "base/strings/stringprintf.h"
#include "base/strings/utf_string_conversions.h"
#include "base/task/post_task.h"
#include "base/task/thread_pool/thread_pool_instance.h"

#include "base/threading/thread_task_runner_handle.h"
#include "base/time/time.h"

#include "third_party/zlib/contrib/minizip/unzip.h"

#include "crypto/aead.h"

#include "net/base/ip_address.h"
#include "net/base/ip_endpoint.h"
#include "net/base/sockaddr_storage.h"
#include "net/ssl/ssl_config_service.h"
#include "net/ssl/ssl_connection_status_flags.h"

#include "net/base/load_flags.h"
#include "net/base/request_priority.h"
#include "net/cert/cert_verifier.h"
#include "net/cert/ct_policy_enforcer.h"
#include "net/cert/do_nothing_ct_verifier.h"
#include "net/dns/host_resolver.h"
#include "net/dns/host_resolver_proc.h"
#include "net/dns/host_resolver_source.h"
#include "net/dns/public/dns_query_type.h"

#include "net/cert/cert_verify_proc.h"
#include "net/cert/cert_verify_proc_builtin.h"
#include "net/http/http_status_code.h"
#include "net/http/transport_security_state.h"
#include "net/socket/client_socket_factory.h"
#include "net/socket/client_socket_handle.h"
#include "net/socket/socket_descriptor.h"
#include "net/socket/tcp_client_socket.h"
#include "net/socket/tcp_server_socket.h"
#include "services/network/public/cpp/server/http_server_request_info.h"

#include "net/proxy_resolution/proxy_config_service_fixed.h"
#include "net/traffic_annotation/network_traffic_annotation.h"
#include "net/url_request/url_fetcher.h"
#include "net/url_request/url_fetcher_delegate.h"
#include "net/url_request/url_request.h"
#include "net/url_request/url_request_context.h"
#include "net/url_request/url_request_context_builder.h"
#include "net/url_request/url_request_context_getter.h"
//#include "net/url_request/url_request_status.h"

#ifdef XPATCH_V8
#include "gin/array_buffer.h"
#include "gin/modules/console.h"
#include "gin/try_catch.h"
#include "gin/v8_initializer.h"
#include "v8/include/v8.h"
#endif

// md: message digest
const unsigned char g_apk_fingerprint_md[] = {
    0xa7, 0xae, 0xd9, 0x99, 0x25, 0x94, 0x02, 0x70, 0x13, 0x27, 0xda,
    0x3b, 0xc9, 0x93, 0xa2, 0x3b, 0xc1, 0xac, 0xee, 0x51, 0x4f, 0x9f,
    0x75, 0xca, 0x09, 0x51, 0x5a, 0x58, 0x2f, 0x6f, 0xf9, 0xdd};

static void* doBackendTestTask(void* xbackend_ptr) {
  XBackend* xbackend = reinterpret_cast<XBackend*>(xbackend_ptr);
#ifndef SINGLE_IO_THREAD
  // TODO COMMENT OUT
  // base::MessageLoop message_loop(base::MessagePumpType::IO);
#endif
  if (xbackend != NULL) {
    xbackend->doBackendTest();
  }
  return xbackend_ptr;
}

XPatch::XPatch(int nt_threshold,
               const std::string& language,
               const std::string& data_dir,
               const std::string& xbackend_path,
               const std::string& pattern)
    : sni_host_("example.com"),
      disabled_cipher_suite_(0),
      language_(language),
      data_dir_(data_dir)
      // TODO commented out
      //,message_loop_(new base::MessageLoop(base::MessagePumpType::IO))
{
  nt_threshold_ = nt_threshold;
  is_fingerprint_matched_ = false;

  if (language_.empty()) {
    language_.assign("zh");
  }

  if (apk_file_.empty()) {
#ifdef OS_ANDROID
    apk_file_.assign("/data/local/tmp/ChromeModernPublic.apk");
#else
    apk_file_.assign("/tmp/ChromeModernPublic.apk");
#endif
  }

  if (data_dir_.empty()) {
#ifdef OS_ANDROID
    data_dir_.assign("/data/local/tmp");
#else
    data_dir_.assign("/tmp");
#endif
  }

  base::FilePath data_dir_filepath(data_dir_);
  data_dir_filepath = data_dir_filepath.Append("startup_page");
  if (language_.compare("fa") == 0) {
    data_dir_filepath = data_dir_filepath.Append("index.en.html");
  } else if (language_.compare("en") == 0) {
    data_dir_filepath = data_dir_filepath.Append("index.en.html");
  } else {
    data_dir_filepath = data_dir_filepath.Append("index.cn.html");
  }
  if (!ReadFileToString(data_dir_filepath, &startup_page_)) {
    startup_page_.assign("");
  }

#ifdef XPATCH_V8
  if (!holder_) {
#ifdef V8_USE_EXTERNAL_STARTUP_DATA
    gin::V8Initializer::LoadV8Snapshot();
    gin::V8Initializer::LoadV8Natives();
#endif
    gin::IsolateHolder::Initialize(gin::IsolateHolder::kNonStrictMode,
                                   gin::IsolateHolder::kStableV8Extras,
                                   gin::ArrayBufferAllocator::SharedInstance());

    holder_.reset(new gin::IsolateHolder(
        base::ThreadTaskRunnerHandle::Get(), gin::IsolateHolder::kUseLocker,
        gin::IsolateHolder::kAllowAtomicsWait,
        gin::IsolateHolder::IsolateType::kUtility,
        gin::IsolateHolder::IsolateCreationMode::kCreateSnapshot));
  }
#endif

  checkApkFingerprint(apk_file_);
  randomizeSNIHost();
  randomizeCiphers();
  loadCACerts();
  loadBackends(xbackend_path, pattern);

  /*
// Instantiate a TaskScheduler with 6 threads in each of its 3 pools
constexpr int kMaxThreads = 5;
const base::TimeDelta kSuggestedReclaimTime = base::TimeDelta::Max();
const base::SchedulerWorkerPoolParams worker_pool_params(
    kMaxThreads, kSuggestedReclaimTime);
// const SchedulerWorkerPoolParams& background_worker_pool_params_in,
// background_blocking_worker_pool_params_in,
// foreground_worker_pool_params_in, foreground_blocking_worker_pool_params_in
#ifndef XPATCH_CLI
base::TaskScheduler::SetInstance(
    std::make_unique<::base::internal::TaskSchedulerImpl>("xpatch-service"));
#else
base::TaskScheduler::SetInstance(
    std::make_unique<::base::internal::TaskSchedulerImpl>("xpatch-cli"));
#endif

// b TaskSchedulerImpl::Start
// TaskSchedulerService, worker_pools_[FOREGROUND],
// worker_pools_[FOREGROUND_BLOCKING]
base::TaskScheduler::GetInstance()->Start(
    {worker_pool_params, worker_pool_params, worker_pool_params,
     worker_pool_params});

#ifndef XPATCH_CLI
base::ScopedClosureRunner cleanup(
    base::BindOnce([] { base::TaskScheduler::GetInstance()->Shutdown(); }));
#endif
*/

// auto ThreadPoolImpl threadPool("xpatchcli");
// ThreadPoolInstance::set(threadPool);
// TODO crash on arm and desktop
  base::ThreadPoolInstance::CreateAndStartWithDefaultParams("xpatchcli");
  /*
  base::CreateSingleThreadTaskRunner({content::BrowserThread::IO});
  base::ThreadPool::CreateSingleThreadTaskRunner(
        {base::TaskPriority::USER_BLOCKING,
         base::TaskShutdownBehavior::SKIP_ON_SHUTDOWN},
         base::SingleThreadTaskRunnerThreadMode::DEDICATED);
  */
}

XPatch::~XPatch() {
  for (auto it = xbackends_.cbegin(); it != xbackends_.cend(); ++it) {
    delete *it;
  }
}

void XPatch::testBackends() {
  bool is_matched = true;
#ifndef SINGLE_IO_THREAD
  pthread_t tid;
#endif

  //#ifndef SINGLE_IO_THREAD
  // pthread_create(&tid, NULL, doFetchConfigTask, this);
  // pthread_setname_np(tid, "doFetchConfigTask");
  //#else
  this->gitClone();
  //#endif

  pthread_setname_np(pthread_self(), "main");

#ifndef XPATCH_CLI
  is_matched = is_fingerprint_matched_;
#endif
  for (auto it = xbackends_.cbegin(); it != xbackends_.cend(); ++it) {
    if (is_matched) {
#ifndef SINGLE_IO_THREAD
      pthread_create(&tid, NULL, doBackendTestTask,
                     reinterpret_cast<void*>(*it));
      // see tools/win/ShowThreadNames/ReadMe.txt
      pthread_setname_np(tid, (*it)->getThreadName().c_str());
#else
      doBackendTestTask(reinterpret_cast<void*>(*it));
#endif
    }
  }

#ifdef XPATCH_CLI
  base::RunLoop run_loop;
  run_loop.Run();
  // run_loop.RunUntilIdle();
#endif
}

void XPatch::pushMessage(int message_type,
                         int message_payload_int,
                         const std::string& message_payload_str) {
  if (message_cb_ != NULL) {
    (*message_cb_)(message_type, message_payload_int, message_payload_str);
  }
}

void XPatch::gitClone() {
  std::string git_repo_url("https://github.com/mmmb1/single_cn.git");
  if (language_.compare("fa") == 0) {
    git_repo_url.assign("https://github.com/mmmb1/single_fa.git");
  } else if (language_.compare("en") == 0) {
    git_repo_url.assign("https://github.com/mmmb1/single_en.git");
  }
  std::string startup_page_dir(data_dir_ + "/startup_page");
  if (doGitClone(git_repo_url, startup_page_dir)) {
    pushMessage(MSG_TYPE_START_PAGE, 1, "GIT");
  }
}

bool XPatch::doGitClone(std::string& uri, std::string& git_repo_path) {
  base::FilePath git_repo_filepath(git_repo_path);
  if (!base::DeleteFile(git_repo_filepath)) {
    MYLOG("failed to delete git repo directory %s\n", git_repo_path.c_str());
  }
  // TODO do libgit2 clone
  return 0;
}

void XPatch::initBadIpAddress() {
  std::string node_info_path = data_dir_;
  // TODO 5 is for what?
  node_info_path.replace(node_info_path.end() - 5, node_info_path.end(),
                         "app_chrome_nodeinfo.txt");
  base::FilePath node_info_filepath(node_info_path);
  std::string contents;
  if (PathExists(node_info_filepath) &&
      ReadFileToString(node_info_filepath, &contents)) {
    std::vector<std::string> lines = base::SplitString(
        contents, "\n", base::KEEP_WHITESPACE, base::SPLIT_WANT_ALL);
    for (auto line : lines) {
      if (line.size() <= 0) {
        continue;
      }
      std::vector<std::string> parts = base::SplitString(
          line, ",", base::KEEP_WHITESPACE, base::SPLIT_WANT_ALL);
      // TODO line format
      int bps = std::stoi(parts[1]);
      int n_req = std::stoi(parts[2]);
      // TODO 10 is for what?
      if (n_req > 0 && bps <= 10) {
        bad_ip_address_ = parts[0];
        break;
      }
    }
  }
}

void XPatch::checkApkFingerprint(std::string& apk_path) {
  unzFile zf = unzOpen(apk_path.c_str());
  if (zf == NULL) {
    MYLOG("failed to check apk fingerprint at %s\n", apk_path.c_str());
    return;
  }
  unz_global_info gi;
  unz_file_info fi;

  unzGetGlobalInfo(zf, &gi);
  for (unsigned long i = 0; i < gi.number_entry;  // NOLINT(runtime/int)
       i++) {
    char filename[256] = {0};
    unzGetCurrentFileInfo(zf, &fi, filename, 256, NULL, 0, NULL, 0);
    if (filename[0] == 'M' && (strcmp(filename, "META-INF/CUSTOM.RSA") == 0 ||
                               strcmp(filename, "META-INF/CERT.RSA") == 0)) {
      unsigned char buf[204800] = {0};
      int n_read = 0;
      unzOpenCurrentFile(zf);
      n_read = unzReadCurrentFile(zf, buf, sizeof(buf));
      if (n_read > 0) {
        BIO* in = BIO_new_mem_buf(buf, n_read);
        PKCS7* p7 = d2i_PKCS7_bio(in, NULL);
        if (OBJ_obj2nid(p7->type) == NID_pkcs7_signed) {
          unsigned char md[EVP_MAX_MD_SIZE] = {0};
          unsigned int fp_len = 0;
          STACK_OF(X509)* certs = p7->d.sign->cert;
          // int cert_num = sk_X509_num(certs);
          X509* cert = sk_X509_value(certs, 0);
          X509_digest(cert, EVP_sha256(), md, &fp_len);
          if (memcmp(g_apk_fingerprint_md, md, fp_len) == 0)
            is_fingerprint_matched_ = true;
#ifdef XPATCH_CLI
          for (unsigned int j = 0; j < fp_len; j++)
            printf("0x%02x,", md[j]);
          printf("\n");
#endif
        }
        BIO_free(in);
      }
      unzCloseCurrentFile(zf);
    }
    unzGoToNextFile(zf);
  }
  unzClose(zf);
}

void XPatch::randomizeSNIHost() {
  int domain_prefix_rand_idx = base::RandInt(0, DOMAIN_PREFIX_SIZE - 1);
  int sld_rand_idx = base::RandInt(0, DOMAIN_SLD_SIZE - 1);
  int tld_rand_idx = base::RandInt(0, DOMAIN_TLD_SIZE - 1);

  sni_host_.clear();
  sni_host_.append(g_domain_prefixes[domain_prefix_rand_idx]);
  sni_host_.append(".");
  sni_host_.append(g_domain_slds[sld_rand_idx]);
  sni_host_.append(".");
  sni_host_.append(g_domain_tlds[tld_rand_idx]);
}

void XPatch::randomizeCiphers() {
  int rand_cipher_idx = base::RandInt(0, sizeof(g_ssl_ciphers) - 1);
  disabled_cipher_suite_ = g_ssl_ciphers[rand_cipher_idx];
}

void XPatch::loadBackends(const std::string& extra_xbackend_path,
                          const std::string& include_pattern) {
  crypto::Aead::AeadAlgorithm alg =
      crypto::Aead::AES_128_CTR_HMAC_SHA256;  // GetParam();
  crypto::Aead aead(alg);
  std::string key(AEAD_KEY, 0, aead.KeyLength());
  aead.Init(&key);
  // TODO 12 is for pos?
  std::string nonce(AEAD_NONCE, 12, aead.NonceLength());

  std::string xbackend_path = extra_xbackend_path;
  std::string default_xbackend_path = data_dir_ + "/" + g_backend_filename;
  base::FilePath default_xbackend_filepath(default_xbackend_path);
  if (PathExists(default_xbackend_filepath)) {
    MYLOG("loadBackends from %s instead of %s\n", default_xbackend_path.c_str(),
          xbackend_path.c_str());
    xbackend_path.assign(default_xbackend_path);
  } else {
    MYLOG("loadBackends default_xbackend_path not found: %s\n",
          default_xbackend_path.c_str());
  }

  if (xbackend_path.empty()) {
    xbackend_data_.assign((const char*)xbackend_data, xbackend_data_len);
    MYLOG("loadBackends from bundled assets\n");
  } else {
    base::ReadFileToString(base::FilePath(xbackend_path), &xbackend_data_);
  }

  std::istringstream iss(xbackend_data_);
  std::string line;

  while (getline(iss, line)) {
    if (include_pattern.empty() ||
        line.find(include_pattern) != std::string::npos) {
      // TODO no duplicate host line when multi-threading(database is locked)
      XBackend* xbackend = new XBackend(line, sni_host_, disabled_cipher_suite_,
                                        nt_threshold_, data_dir_);
      if (xbackend->getType() == HOST_TYPE_DEPRECATED) {
        delete xbackend;
      } else {
        xbackends_.push_back(xbackend);
        // MYLOG("load xbackend: %s %s, %u, %d, %s\n", line.c_str(),
        //      sni_host_.c_str(), disabled_cipher_suite_, nt_threshold_,
        //      data_dir_.c_str());
      }
    } else {
      MYLOG("loadBackends ignores line %s\n", line.c_str());
    }
  }

#ifdef NO_SHUFFLE_BACKENDS
  std::random_device rd;
  auto rng = std::default_random_engine{rd()};
  std::shuffle(xbackends_.begin(), xbackends_.end(), rng);
#endif
}

void XPatch::loadCACerts() {
  X509* cert = NULL;
  BIO* bio = BIO_new_mem_buf(cacert_pem, cacert_pem_len);

  do {
    cert = PEM_read_bio_X509(bio, NULL, 0, NULL);
    ca_certs_.push_back(cert);
  } while (cert != NULL);
  BIO_free(bio);
}

// const std::string& XPatch::getSNIHost() {
//  return sni_host_;
//}
