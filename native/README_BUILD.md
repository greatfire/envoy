
Building Cronet for Envoy
=========================

This is documentation for developers on setting up a build environment for Envoy's version of Cronet and building it.

Requirements:
-------------

The requirements are documented in more detail in the first link, but you'll want an amd64/x86_64/x64 Linux system running a recent LTS version or a Debian system (I think both bullsey and bookworm work at the time of this writing), around 150-200GB of free space, and a lot of patience. A fast network connection and fast computer will help, but more patience can be substituted

Using Docker or some kind of VM for a build system can be helpful to have a repeatable build setup

You just need a couple of things installed: `build-essential`, `git`, and `lsb-release`


Get the code
------------

Follow the directions here to install `depot_tools`: https://chromium.googlesource.com/chromium/src/+/main/docs/linux/build_instructions.md

We'll make one slight change when we get the "Get the code" section. Make a directory as documented, `mkdir ~/chromium && cd ~/chromium` then run this instead:

```bash
fetch --nohooks android
```

(I'm not totally sure this is still necessary, but it sets the build target to Android)

Per Google's docs, "Expect the command to take 30 minutes on even a fast connection, and many hours on slower ones." This will likely take many hours.

If it fails, try running `gclient sync --nohooks` repeatedly until it doesn't fail... or `rm -r` it and try again

It's possible to run the fetch command with `--nohistory`, to perfom a shallow clone. If you do so, [this script](https://github.com/greatfire/envoy/blob/master/native/checkout-to-tag.sh) attempts to fetch the needed tag, though it may not actually work out to be much faster, and you need to fetch each tag as needed. The original Envoy build did do this, and it may actually work out better in a more limited network situation than my fast connection in California :)

Whatever you do, maybe plan to have it run overnight, it's going to take a while.


Install dependencies:
---------------------

Once you've gotten the code, we have a couple more steps. Install the depenecies:

```bash
./build/install-build-deps.sh --arm --lib32 --no-nacl
```

This figures out what apt packages to install, and runs `apt` to install them. We ask for support to target 32 bit ARM and x86, and we don't need NACL for Cronet. Now we run the hooks:

```bash
gclient runhooks
```

This will take a while to update things. Note that what we did here was delay running the hooks until after running `install-build-deps.sh`, running the hooks isn't something you need to explicitly do after this.


Updating Cronet
---------------

Whenever you change versions of the Chromium sources, you need to "sync" the build environment. If you've just completed the install instructions, you should be ready to go, but at some point, you'll want to upgrade. Also, any time you switch the version of the chromium sources you're using, you need to re-do this step.

You can, of course, always just `git pull` to get the newest sources, but you can also use the [checkout-to-tag.sh script](https://github.com/greatfire/envoy/blob/master/native/checkout-to-tag.sh) to fetch just the particlar verstion you need, this can be substantially faster.

If you're looking for a new version to try, we usually pick one listed on [this site](https://chromiumdash.appspot.com/releases?platform=Android)

Once you've switched the main git repo, you'll need to run:

```bash
gclient sync
```

If you get an error, just keep re-running `gclient sync` until it completes successfully.


Building older versions
-----------------------

As time goes on, sometimes the current version the `depot_tools` repo will not work with older version of the Chromium sources. You can use the [checkout-to-tag](https://github.com/greatfire/envoy/blob/master/native/checkout-to-tag.sh) script (or run the appropriate commands by hand) to sync your depot_tools repo with the current chromium checkout


Building Cronet
---------------

In many cases, you'll just want to run the [build script](https://github.com/greatfire/envoy/blob/master/native/build_cronet.sh), but if you're doing development, or need to debug your dev environment, you need to build cronet for just one platform at a time. Note that you only need to do the `gn gen` step once, though it doesn't hurt to re-run, and may update things on version changes. From your `chromium/src` directory:

To build the Linux command line app (see: components/cronet/native/sample/)

```bash
gn gen out/Desktop
autoninja -C out/Desktop cronet_sample
./out/Desktop/cronet_sample # run it
```

To build the Android package (this is the target Envoy builds) for ARM64 debug (the default)

```bash
./components/cronet/tools/cr_cronet.py gn gen -d out/Cronet-arm64-debug
gn args out/Cronet-arm64-debug # set "use_remoteexec = false" and save
autoninja -C out/Cronet-arm64-debug cronet_package
# output in out/Cronet-arm64-debug/cronet/libs/
```

build the release version for ARM (32-bit)

```bash
./components/cronet/tools/cr_cronet.py gn gen -d out/Cronet-arm-release --arm --relase
gn args out/Cronet-arm-release # set "use_remoteexec = false" and save
autoninja -C out/Cronet-arm64-debug cronet_package
# output in out/Cronet-arm-release/cronet/libs
```

There's a simple demo app for Android as well, build it for x64:

```bash
./components/cronet/tools/cr_cronet.py gn gen -d out/Cronet-x64-debug --x64
gn args out/Cronet-x64-debug # set "use_remoteexec = false" and save
autoninja -C out/Cronet-x64-debug cronet_sample_apk
# output in out/Cronet-x64-debug/ TODO, probably cronet/aar or something?
```
