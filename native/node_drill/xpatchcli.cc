#include "iostream"

#include "xbackend.h"
#include "xnode.h"
#include "xpatch.h"

#include "base/at_exit.h"
#include "base/command_line.h"
#include "base/files/file_path.h"
#include "base/files/file_util.h"
// TODO COMMENT OUT
//#include "base/message_loop/message_loop.h"
#include "base/run_loop.h"
#include "base/strings/pattern.h"
#include "base/strings/string_number_conversions.h"
#include "base/strings/string_split.h"
#include "base/strings/stringprintf.h"
// #include "base/android/locale_utils.h"
// #include "base/android/path_utils.h"
#include "base/memory/weak_ptr.h"
#include "base/rand_util.h"
#include "base/task/thread_pool/thread_pool_instance.h"
#include "crypto/aead.h"
#include "net/base/ip_endpoint.h"

#include "third_party/icu/source/common/unicode/locid.h"
#include "third_party/icu/source/common/unicode/uchar.h"
#include "third_party/icu/source/common/unicode/uscript.h"
#include "third_party/icu/source/i18n/unicode/coll.h"

#include "base/json/json_writer.h"
#include "base/values.h"

#define AEAD_SEAL_ADDITIONAL_DATA "0123456789012345678"

const char g_usage[] =
    "Usage:\n"
    "  --xbackend-input=<xbackend path>\n"
    "    <xbackend path> is a xbackend file loaded by xpatchcli\n"
    "  --xbackend-output=<xbackend encrypt path>\n"
    "    <xbackend encrypt path> is a encrypted xbackend file\n"
    "  --data-dir=<sqlite data directory>\n"
    "    where to put sqlite dbs\n"
    "  --include-pattern=<include-pattern>\n"
    "    plain substring pattern used by xpatchcli to filter xbackend\n"
    "  --nt-threshold=<msec>\n"
    "    <msec> set nt threshold value for Node Test\n"
    "  --git-repo-uri=<repo>\n"
    "    <repo> git clone from test repo, if empty, use a default repo\n"
    "  --v3test\n"
    "    do v3/get/xxx REST API test\n"
    "\n";

static void message_callback_func(int message_type,
                                  int message_payload_int,
                                  const std::string& message_payload_str) {
  std::cout << pthread_self()
            << " message_callback_func: message_type=" << message_type
            << ", message_payload_int=" << message_payload_int
            << ", message_payload_str=" << message_payload_str << std::endl;
}

bool encrypt_xbackend(std::string xbackend_input, std::string xbackend_output) {
  bool is_succeed = false;
  crypto::Aead::AeadAlgorithm alg = crypto::Aead::AES_128_CTR_HMAC_SHA256;
  crypto::Aead aead(alg);
  std::string key(AEAD_KEY, 0, aead.KeyLength());
  aead.Init(&key);
  std::string nonce(AEAD_NONCE, 12, aead.NonceLength());

  std::string contents;
  bool is_read_succeed =
      base::ReadFileToString(base::FilePath(xbackend_input), &contents);
  if (is_read_succeed && !contents.empty()) {
    std::string cipher_text;
    aead.Seal(contents, nonce, AEAD_SEAL_ADDITIONAL_DATA, &cipher_text);
    int bytes_write_count =
        base::WriteFile(base::FilePath(xbackend_output), cipher_text.c_str(),
                        cipher_text.size());
    if (bytes_write_count > 0) {
      MYLOG("ENCRYPT xbackend %s --> %s n_write = %d Ok!\n",
            xbackend_input.c_str(), xbackend_output.c_str(), bytes_write_count);
      MYLOG("RUN 'xxd -i %s > xbackend_data.h' to generate header file",
            xbackend_output.c_str());
      is_succeed = true;
    }
  }

  if (!is_succeed) {
    MYLOG(
        "ENCRYPT xbackend %s failed, check --xbackend-input && "
        "--xbackend-output \n",
        xbackend_input.c_str());
  }
  return is_succeed;
}

void print_usage() {
  MYLOG("xpatchcli v1.0.0 \n");
  MYLOG(g_usage);
}

int main(int argc, char* argv[]) {
  base::AtExitManager at_exit_manager;
  int nt_threshold = 5000;
  if (!base::CommandLine::Init(argc, argv)) {
    MYLOG("xpatchcli CommandLine Init failed\n");
    return 1;
  }
  base::CommandLine& command_line = *base::CommandLine::ForCurrentProcess();
  // base::CommandLine::StringVector args = command_line.GetArgs();
  if (command_line.HasSwitch("help")) {
    print_usage();
    return 2;
  }

  // v8_demo(argc, argv);
  std::string xbackend_input =
      command_line.GetSwitchValueASCII("xbackend-input");
  if (!xbackend_input.empty()) {
    std::string xbackend_output =
        command_line.GetSwitchValueASCII("xbackend-output");
    return encrypt_xbackend(xbackend_input, xbackend_output);
  }

  const icu::Locale& locale = icu::Locale::getDefault();
  std::string language = locale.getLanguage();
  MYLOG("xpatchcli.Locale (language=%s, country=%s)\n", language.c_str(),
        locale.getCountry());

  std::string include_pattern =
      command_line.GetSwitchValueASCII("include-pattern");
  std::string nt_threshold_str =
      command_line.GetSwitchValueASCII("nt-threshold");
  if (!nt_threshold_str.empty()) {
    nt_threshold = stoi(nt_threshold_str);
  }

  std::string data_dir = command_line.GetSwitchValueASCII("data-dir");
  XPatch patch(nt_threshold, language, data_dir, xbackend_input,
               include_pattern);

  if (command_line.HasSwitch("git-repo-uri")) {
    std::string git_repo_uri = command_line.GetSwitchValueASCII("git-repo-uri");
    if (git_repo_uri.empty()) {
      git_repo_uri.assign("https://github.com/google/crc32c.git");
    }
    std::string path("/data/local/tmp/crc32c");
    std::string err_str("");
    // patch.doGitClone(git_repo_uri, path, err_str);
  }

  if (command_line.HasSwitch("v3test")) {
    patch.gitClone();
    MYLOG("gitClone\n");
  }

  patch.setMessageCallBack(message_callback_func);
  patch.testBackends();

  return 0;
}

int parse_cidr(int argc, char* argv[]) {
  size_t prefix_length_in_bits = 0;
  net::IPAddress ip_address;
  bool is_parsed =
      ParseCIDRBlock("1.2.3.4", &ip_address, &prefix_length_in_bits);
  MYLOG("%s %zu %d\n", ip_address.ToString().c_str(), prefix_length_in_bits,
        is_parsed);
  return static_cast<int>(is_parsed);
}

int print_json(int argc, char* argv[]) {
  // https://bugs.chromium.org/p/chromium/issues/detail?id=726752
  base::DictionaryValue options;
  options.SetPath({"HostResolverRules", "host_resolver_rules"},
                  base::Value("MAP * 127.0.0.1"));
  std::string options_json;
  base::JSONWriter::Write(options, &options_json);
  MYLOG("json: %s\n", options_json.c_str());
  return 0;
}

int test_node(int argc, char* argv[]) {
  base::AtExitManager at_exit_manager;

  base::ThreadPoolInstance::CreateAndStartWithDefaultParams("xpatchcli");
  XNode node("allowed.example.com", g_ssl_ciphers[0], "forbidden.example.com", "/");
  // TODO COMMENT OUT
  // base::MessageLoop message_loop(base::MessagePumpType::IO);

  net::IPAddress ip_address;
  if (ip_address.AssignFromIPLiteral("1.2.3.4")) {
    int msec = node.doCrTest(ip_address);
    MYLOG("node crTest costs %dms\n", msec);
    return msec;
  }
  return -1;
}

int test_backend(int argc, char* argv[]) {
  base::AtExitManager at_exit_manager;
  base::ThreadPoolInstance::CreateAndStartWithDefaultParams("xpatchcli");
  // TODO COMMENT OUT
  // base::MessageLoop message_loop(base::MessagePumpType::IO);

  int nt_threshold = 5000;
  XBackend xbackend(
      "8: forbidden.example.com/,CN=a248.e.akamai.net,CN=Verizon "
      "Akamai,1.2.3.4/32",
      "allowed.example.com", g_ssl_ciphers[0], nt_threshold, "/tmp");
  xbackend.setMessageCallBack(message_callback_func);
  xbackend.doBackendTest();
  return 0;
}
