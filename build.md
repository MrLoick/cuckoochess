# Introduction #

This page explains how to build CuckooChess and DroidFish using eclipse in linux and windows.

# Preparations #

To build the CuckooChess chess engine, you need an eclipse installation with support for svn. I use "Subversive SVN Team Provider" and "SVNKit".

To build the DroidFish Android app, you first need to install the Android SDK and the Android NDK from
http://developer.android.com/sdk/index.html.

# Building CuckooChess #

  1. Checkout the CuckooChessEngine project:
    1. In eclipse, select File -> New -> Other -> Checkout Projects from SVN.
    1. Create a new repository location, http://cuckoochess.googlecode.com/svn/trunk/.
    1. Select CuckooChessEngine, click Finish.
  1. If java is installed in a non-standard location on your computer or you are using windows, change the BinBook\_builder eclipse builder:
    1. Right-click on CuckooChessEngine in the package explorer, select Properties.
    1. Click Builder -> BinBook\_builder -> Edit.
    1. Change Location to point to your java executable. In windows, this is likely C:\Windows\System32\java.exe.
  1. Checkout the CuckooChess project from http://cuckoochess.googlecode.com/svn/trunk/.
  1. When the CuckooChess project is built (which will happen automatically if "build automatically" is enabled) a jar file is created in workspace\_path/CuckooChess/deploy/CuckooChess.jar.

# Building DroidFish #

  1. DroidFish depends on the CuckooChessEngine project, so checkout that project first.
  1. Checkout the DroidFish project from http://cuckoochess.googlecode.com/svn/trunk/.
  1. Define the NDK environment variable to point to your NDK installation directory, or modify the DroidFish Native\_Builder to point to the ndk-build script in the NDK installation directory. In windows, point to the ndk-build.cmd script in the NDK installation directory.

If you plan to make modifications to the source code, you may also want to checkout the DroidFishTest project from http://cuckoochess.googlecode.com/svn/trunk/.