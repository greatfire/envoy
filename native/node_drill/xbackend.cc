#include "xbackend.h"
#include "xnode.h"
#include "xpatch.h"

#include <algorithm>
#include <memory>
#include <random>
#include <string>
#include <utility>
#include <vector>

#include "base/strings/string_split.h"
// #include "base/strings/string_util.h"
#include "base/files/file_util.h"
#include "base/strings/stringprintf.h"

#include "net/proxy_resolution/proxy_config_service_fixed.h"
#include "net/url_request/url_fetcher.h"
#include "net/url_request/url_request_context.h"
#include "net/url_request/url_request_context_builder.h"
#include "net/url_request/url_request_context_getter.h"

#include "base/task/thread_pool/thread_pool_instance.h"

#include "base/hash/md5.h"

#include "sql/database.h"
#include "sql/initialization.h"
#include "sql/statement.h"

#include "base/threading/thread_task_runner_handle.h"
#include "base/run_loop.h"

pthread_mutex_t g_backends_mutex = PTHREAD_MUTEX_INITIALIZER;
pthread_mutex_t g_backend_update_mutex = PTHREAD_MUTEX_INITIALIZER;
size_t g_node_size = 0;
std::vector<XBackend*> g_backends;
size_t g_backend_size = 0;
size_t g_config_update_when_size = 1;  // TODO or randInt(1, 3) ?

XBackend::XBackend(const std::string& xbackend_line,
                   const std::string& sni_host,
                   uint16_t disabled_cipher_suite,
                   int nt_threshold,
                   const std::string& data_dir)
    : sni_host_(sni_host), nt_threshold_(nt_threshold), data_dir_(data_dir) {
  disabled_cipher_suite_ = disabled_cipher_suite;
  // 8: allowed.example.com/,CN=foo.com,CN=Bar,1.2.3.4/23,1.2.3.4
  std::vector<std::string> parts = base::SplitString(
      xbackend_line, ":", base::KEEP_WHITESPACE, base::SPLIT_WANT_ALL);
  if (parts.size() != 2) {
    MYLOG("invalid xbackend line: %s\n", xbackend_line.c_str());
  } else {
    priority_ = std::stoi(parts[0]);
    std::vector<std::string> line_details = base::SplitString(
        parts[1], ",", base::TRIM_WHITESPACE, base::SPLIT_WANT_ALL);
    std::vector<std::string> url_detail = base::SplitString(
        line_details[0], "/", base::KEEP_WHITESPACE, base::SPLIT_WANT_ALL);
    host_ = url_detail[0];
    type_ = host_.at(0);
    base_url_ = line_details[0].substr(host_.length(), -1);
    std::string thread_name_full =
        base::StringPrintf("NT %.2s", line_details[0].c_str());
    thread_name_full.resize(15);  // 16 including the tailing null.
    thread_name_ = thread_name_full.c_str();

    if (type_ == HOST_TYPE_DEPRECATED || type_ == HOST_TYPE_B) {
      host_ = host_.substr(1, -1);
    }

    base::MD5Context ctx;
    base::MD5Init(&ctx);
    base::MD5Update(&ctx, host_);
    base::MD5Digest digest;
    base::MD5Final(&digest, &ctx);
    host_md5_ = base::MD5DigestToBase16(digest);
    std::string db_path = data_dir_ + "/" + host_md5_ + ".db";
    base::FilePath db_file_path(db_path);
    if (db_.Open(db_file_path)) {
      // equivalent to XNode
      // TODO create index on ip, snihost, cipher, host ?
      // TODO migrate old table using alter table statements?
      if (!db_.Execute("CREATE TABLE IF NOT EXISTS ipt (tm INTEGER, "
                       "score INTEGER, ip TEXT, snihost TEXT, cipher TEXT, "
                       "host TEXT DEFAULT '', base_url TEXT DEFAULT '', "
                       "priority INTEGER DEFAULT 0);")) {
        MYLOG("failed to create table %s\n", db_path.c_str());
      }
      int ts_one_week_ago = time(NULL) - 86400 * 7;
      char delStmt[1024] = {0};
      snprintf(delStmt, sizeof(delStmt), "DELETE FROM ipt WHERE tm < %d;",
               ts_one_week_ago);
      if (!db_.Execute(delStmt)) {
        MYLOG("failed to delete from table %s\n", db_path.c_str());
      }
    }

    for (unsigned long i = 3; i < line_details.size();  // NOLINT(runtime/int)
         i++) {
      CIDR* cidr = new CIDR(line_details[i]);
      cidrs_.push_back(cidr);
    }

#ifdef NO_SHUFFLE_CIDRS
    std::random_device rd;
    auto rng = std::default_random_engine{rd()};
    std::shuffle(cidrs_.begin(), cidrs_.end(), rng);
#endif
  }
  // nt_err_ = nt_ok_err_ = nt_ok_ = 0;
  progress_ = 0;

  /*
  ssl_context_.cert_verifier = new NTCertVerifier();
  ssl_context_.cert_transparency_verifier = new DoNothingCTVerifier;
  ssl_context_.transport_security_state = new TransportSecurityState; // new
  TransportSecurityState 2 ssl_context_.ct_policy_enforcer = new
  NTCTPolicyEnforcer; ssl_context_.ssl_session_cache_shard = "shard";
  */


// TODO reset disabled_cipher_suite
// std::vector<uint16_t> disabled_cipher_suites;
// disabled_cipher_suites.push_back(disabled_cipher_suite);
// xxx.disabled_cipher_suites = disabled_cipher_suites;
  ssl_context_config_.disabled_cipher_suites.push_back(disabled_cipher_suite_);

  /*
 net::URLRequestContextBuilder context_builder;
 context_builder.set_user_agent(g_user_agent);
 context_builder.DisableHttpCache();
 context_builder.set_proxy_config_service(
     std::make_unique<net::ProxyConfigServiceFixed>(
         net::ProxyConfigWithAnnotation::CreateDirect()));

 auto resolver = net::HostResolver::CreateStandaloneResolver(
     net::NetLog::Get(), net::HostResolver::ManagerOptions(),
     "MAP * 127.0.0.1", true);
 context_builder.set_host_resolver(std::move(resolver));
 std::unique_ptr<NTCertVerifier> cert_verifier(new NTCertVerifier());
 context_builder.SetCertVerifier(std::move(cert_verifier));
 std::unique_ptr<NTSSLConfigService> ssl_config_service(
     new NTSSLConfigService(ssl_context_config_));
 context_builder.set_ssl_config_service(std::move(ssl_config_service));
 // context_ = context_builder.Build();  // new TransportSecurityState 1
 */
  loadNodes();
}

XBackend::~XBackend() {
  for (auto it = cidrs_.cbegin(); it != cidrs_.cend(); ++it) {
    delete *it;
  }
  // TODO close db_?
}

const std::string& XBackend::getThreadName() {
  return thread_name_;
}

const std::string& XBackend::getHost() {
  return host_;
}

char XBackend::getType() {
  return type_;
}

int XBackend::getProgress() {
  return progress_;
}

// int getNodeCount() { return host_ip_addresses_.size(); }

bool XBackend::doBackendTest() {
  int max_nt_threshold = nt_threshold_ + 1700;
  int nt_threshold_step = 80;
  int nt_err_numerator = 30;
  int cidr_index = 0, cidr_total = cidrs_.size();
  char response_flag = '3';
  if (cidr_total <= 0) {
    return false;
  }
  int nt_err_max =
      nt_err_numerator / cidr_total;  // TODO nt_err_max will be zero if
                                      // cidr_total is large enough
  int nt_ok_threshold = 2;
  int nt_ok_err_threshold = 3;
  do {
    for (auto cidr_iterator = cidrs_.cbegin(); cidr_iterator != cidrs_.cend();
         cidr_iterator++, cidr_index++) {
      int nt_threshold = max_nt_threshold - 1700, nt_ok = 0, nt_ok_err = 0,
          nt_err = 0;
      int ip_total = (*cidr_iterator)->getIPSize();
      progress_ = static_cast<int>((cidr_index + 1) * 100.00 / cidr_total);
      for (int ip_seq = 0; nt_threshold < max_nt_threshold &&
                           ip_seq < ip_total && nt_err <= nt_err_max;
           ip_seq++) {
        if (shouldStopNT())
          return true;
        net::IPAddress ip_address = (*cidr_iterator)->getRandAddr();
        XNode node(sni_host_, disabled_cipher_suite_, host_, base_url_);
        int msec = node.doCrTest(ip_address);
        // int msec = doCrTest(ip_address);
        MYLOG(
            "%lu BackendTest: sni=%-20s, host=%-20s, "
            "cidr=%03d/%03d, "
            "ip=%-15s(%03d/%05d), ~cipher_suite_=%d, "
            "timing=%d<%d<%d, node_size=%zu/%zu, nt_err=%d/%03d\n",
            pthread_self(), sni_host_.c_str(), host_.c_str(), cidr_index + 1,
            cidr_total, ip_address.ToString().c_str(), ip_seq + 1, ip_total,
            disabled_cipher_suite_, msec, nt_threshold, max_nt_threshold,
            host_ip_addresses_.size(), g_node_size, nt_err, nt_err_max);
        if (msec > 0 && node.getResponse().size() > 0 &&
            node.getResponse().at(0) == response_flag) {
          if (msec <= nt_threshold) {
            host_ip_addresses_.push_back(
                new HostIpAddress(node.getSNIHost(), ip_address));
            std::string ip_address_str = ip_address.ToString();
            saveNode(ip_address_str, node.getSNIHost(), msec);
            nt_ok++, nt_err = 0, nt_ok_err = 0;

            std::string ga_nt_event =
                host_ + "_" + ip_address_str + "_" + sni_host_;
            pushMessage(MSG_TYPE_COMMAND, 1, ga_nt_event);
            if (addBackend(this, ip_address)) {
              return true;
            }
            updateConfig();

            std::string message_payload_string = base::StringPrintf(
                "%d;%s;https://%s%s;%s", priority_, ip_address_str.c_str(),
                node.getSNIHost().c_str(), base_url_.c_str(), host_.c_str());
            pushMessage(MSG_TYPE_PROXY_INFO, g_node_size,
                        message_payload_string);
            if (nt_ok > nt_ok_threshold)
              break;
          } else {
            nt_threshold += nt_threshold_step;
            if (g_node_size == 0) {
              std::string message_payload_string =
                  base::StringPrintf(" %cS1 ", host_.at(0));
              pushMessage(MSG_TYPE_SHOW_TOAST, nt_ok_err,
                          message_payload_string);
            }
            if (nt_ok_err > nt_ok_err_threshold)
              break;
            nt_ok_err++;
          }
        } else {
          nt_err++;
        }
      } /** foreach ip per CIDR **/
    }   /** foreach CIDR block **/
  } while (g_node_size == 0);
  MYLOG("%lu finished cidr blocks iterating %s: node_size=%zu/%zu\n",
        pthread_self(), host_.c_str(), host_ip_addresses_.size(), g_node_size);
  return false;
}

void XBackend::loadNodes() {
  int ip_address_column_index = 2;
  net::IPAddress ip_address;
  sql::Statement selStmt(
      // TODO group by snihost, cipher
      db_.GetUniqueStatement("SELECT tm, score, ip, snihost, cipher FROM ipt "
                             "GROUP BY ip "
                             "ORDER BY tm LIMIT 60;"));
  while (selStmt.Step()) {
    std::string saved_ip_address =
        selStmt.ColumnString(ip_address_column_index);
    // TODO bad ip address check is disabled
    // if (!patch_/->isBadIpAddress(saved_ip_address)) {
    if (ip_address.AssignFromIPLiteral(saved_ip_address)) {
      MYLOG("%lu load ip from db for host %-30s(%s): %s\n", pthread_self(),
            host_.c_str(), host_md5_.c_str(), ip_address.ToString().c_str());
      // NOTE origin implementation binds to cidr blocks and nt tests.
      cidrs_.insert(cidrs_.begin(), new CIDR(saved_ip_address + "/32"));
    } else {
      MYLOG("%lu invalid saved ip from db for host %s(%s): %s\n",
            pthread_self(), host_.c_str(), host_md5_.c_str(),
            saved_ip_address.c_str());
    }
    //}
  }
}

void XBackend::saveNode(const std::string& ip_address,
                        const std::string& sni_host,
                        int msec) {
  std::string disabled_cipher_suite_str;
  SSLCipherSuiteToString(disabled_cipher_suite_, disabled_cipher_suite_str);
  char insertStmt[4096] = {0};
  // TODO use 'INSERT OR REPLACE INTO'
  snprintf(insertStmt, sizeof(insertStmt),
           "INSERT INTO ipt(tm, score, ip, snihost, cipher) "
           " VALUES(%ld, %d, \"%s\", \"%s\", \"%s\");",
           time(NULL), msec, ip_address.c_str(), sni_host.c_str(),
           disabled_cipher_suite_str.c_str());
  if (db_.Execute(insertStmt)) {
    MYLOG("%lu insert into db for host %s: %s\n", pthread_self(), host_.c_str(),
          insertStmt);
  } else {
    MYLOG("%lu failed to insert into db for host %s: %s\n", pthread_self(),
          host_.c_str(), insertStmt);
  }
}

int XBackend::doHTTPGet(const std::string& path, std::string& resp) {
  HostIpAddress* host_ip_address = host_ip_addresses_.front();
  std::string base_url = base_url_;
  std::string host = host_;
  net::SSLContextConfig ssl_context_config = ssl_context_config_;
  if (host_ip_address == NULL) {
    return 0;
  }

  std::string url =
      base::StringPrintf("https://%s%s%s", host_ip_address->getHost().c_str(),
                         base_url.c_str(), path.c_str());
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
      base::StringPrintf("MAP * %s",
                         host_ip_address->getIPAddress().ToString().c_str()),
      true);
  context_builder.set_host_resolver(std::move(resolver));
  std::unique_ptr<NTCertVerifier> cert_verifier(new NTCertVerifier());
  context_builder.SetCertVerifier(std::move(cert_verifier));
  std::unique_ptr<NTSSLConfigService> ssl_config_service(
      new NTSSLConfigService(ssl_context_config));
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
  host_header.append(host);
  fetcher->AddExtraRequestHeader(host_header);
  fetcher->Start();

  run_loop.Run();
  if (!fetcher->GetResponseAsString(&resp)) {
    MYLOG("failed to get response for %s\n", url.c_str());
  }
  int status_code = fetcher->GetResponseCode();
  MYLOG(YEL
        "%lu HTTPGet: url=%-45s, ip=%-15s, host=%-22s, "
        "status_code=%d, "
        "resp=%.45s\n" COLOR_RESET,
        pthread_self(), url.c_str(),
        host_ip_address->getIPAddress().ToString().c_str(), host.c_str(),
        status_code, resp.c_str());
  return status_code;
}

void XBackend::pushMessage(int message_type,
                           int message_payload_int,
                           const std::string& message_payload_str) {
  if (message_cb_ != NULL) {
    (*message_cb_)(message_type, message_payload_int, message_payload_str);
  }
}

void XBackend::updateConfig() {
  pthread_mutex_lock(&g_backend_update_mutex);
  // only update on first found xbackend
  if (g_node_size != g_config_update_when_size) {
    pthread_mutex_unlock(&g_backend_update_mutex);
    return;
  }

  std::string resp;
  int status_code = doHTTPGet("v3/get/whitelist", resp);
  if (status_code == 200 && !resp.empty()) {
    std::string trimmed_response;
    base::TrimString(resp, "\r\n", &trimmed_response);
    pushMessage(MSG_TYPE_COMMAND, 2, trimmed_response);
  }

  status_code = doHTTPGet("v3/get/xbackend", resp);
  if (status_code == 200 && !resp.empty()) {
    std::string xbackend_path = data_dir_ + "/" + g_backend_filename;
    if (base::WriteFile(base::FilePath(xbackend_path), resp.c_str(),
                        resp.size())) {
      MYLOG(RED
            "xbackend updated to %s, backend_size=%zu, "
            "node_size=%zu/%zu\n" COLOR_RESET,
            xbackend_path.c_str(), g_backend_size, g_config_update_when_size,
            g_node_size);
    }
  }

  pthread_mutex_unlock(&g_backend_update_mutex);
}

bool shouldStopNT() {
  return g_node_size >= MAX_VALID_NODES;
}

bool addBackend(XBackend* xbackend, net::IPAddress ip_address) {
  pthread_mutex_lock(&g_backends_mutex);
  if (std::find(g_backends.begin(), g_backends.end(), xbackend) ==
      g_backends.end()) {
    g_backends.push_back(xbackend);
    g_backend_size++;
  }
  g_node_size++;
  pthread_mutex_unlock(&g_backends_mutex);
  return shouldStopNT();
}

// HostIpAddress* XBackend::firstHostIpAddress() {
//  return host_ip_addresses_.front();
// }
