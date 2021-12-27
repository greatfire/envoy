## On first import

1. run `gl cl format *.cc *.h` and `gn format BUILD.gn`.
2. three secrets found with `rg '[a-z0-9]{15,}' .` are replaced, and xbackend is replaced with sample data.
3. cacert.pem is replaced with a dummy one.
4. ngtool target is added to BUILD.gn(with tools directory removed).
5. cacert_pem.h and xbackend_data.h are removed(for they can be built with Makefile).
6. see first-import.patch for other changes.

## How to run node_drill_cli

### on Desktop

1. cp this directory to `$CHROMIUM_SRC/node_drill`, then add `"//node_drill:node_drill",` to group "gn_all"'s deps in `$CHROMIUM_SRC/BUILD.gn`.
1. run make in `$CHROMIUM_SRC/node_drill` to generate header files.
1. apply friend-url-fetcher.patch
1. cd to $CHROMIUM_SRC, run `gn gen out/Default-Desktop --args="is_debug=true"` and `autoninja -C out/Default-Desktop node_drill_cli`

### on Android

step one and two are the same as above. 
1. cd into $CHROMIUM_SRC, then run `gn gen out/Default --args='target_os="android" target_cpu="arm" is_debug="true" is_component_build="false"'` and `autoninja -C out/Default node_drill_cli` 
2. `adb push out/Default/node_drill_cli /data/local/tmp/`
3. `adb shell chmod +x  /data/local/tmp/node_drill_cli` and `adb shell /data/local/tmp/node_drill_cli`.

### NOTE

- for chrome 72.0.3626.122 and 83.0.4103.76, done.
- for chrome 85.0.4153.2, URLFetcher is deprecated, and constructor and static Create methods are private, so friend XBackend in URLFetcher, see friend-url-fetcher.patch.

### lints
1. `cppcheck --enable=style --language=c++ --suppress=cstyleCast *.cc *.h`,
2. `cpplint --filter='-build/namespaces,-build/include_subdir,-build/include_order,-legal/copyright,-build/header_guard,-readability/todo' *.cc *.h`
    remaining: `Is this a non-const reference? If so, make const or use a pointer`
3. is_tsan = true, is_msan = true
   `invalid argument '-fsanitize=thread' not allowed with '-fsanitize=memory'`, https://stackoverflow.com/questions/36971902/why-cant-clang-enable-all-sanitizers


## TODO

1. [ ] Rename patch to ([node] drill)??
1. [x] Rename ipt(ip test) to nd(node drill), or bt(backend test) or nt(node_test)
1. [x] Rename XBackendItem to XBackend(CDN Account)?
1. [x] Remove using namespace statements
1. [x] Set disabled_cipher_suite_ for XNode/XBackend/Xpatch
1. [x] Make Xbackend could be called directly without XPatch
1. [ ] Add NTURLFetcherDelegate to XBackend
1. [ ] Replace URLFetcher with IPTHelper(SimpleURLLoader)
1. [ ] Support multiple disable cipher suite
1. [x] Shuffle backends and cidrs

## Known bugs

1. In debug build `[0626/022732.786671:FATAL:url_fetcher_core.cc(56)] Check failed: base::ContainsKey(fetchers_, core).`,  
       race condition when two thread try to update g_registry. Set is_debug=false to avoid crash.
    net/url_request/url_fetcher_impl.h: Only one "IO" thread is supported for URLFetcher.
2. `received signal SIGSEGV, Segmentation fault.  0x0000555555a8b6be in net::URLFetcherCore::StartURLRequest() ()`
3. 72 patched: `Found a corrupted memory buffer in MallocBlock (may be offset from user ptr): buffer index: 1, buffer ptr: 0x113c69e31400, size of buffer: 1024`
3. `valgrind --track-origins=yes out/Default-Desktop/node_drill_cli`: `Integer divide by zero at address 0x1009E3DC09`

### arm
1. `F libc    : Fatal signal 11 (SIGSEGV), code 1, fault addr 0x8 in tid 7366 (N Bmanyhats.pao), pid 7356 (main)`, https://monorail-staging.appspot.com/p/chromium/issues/detail?id=75212
