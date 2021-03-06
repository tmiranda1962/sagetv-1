#!/bin/sh
#
# Copyright 2015 The SageTV Authors. All Rights Reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#  http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# Get the version number info
MAJOR_VERSION=`grep MAJOR_VERSION ../java/sage/Version.java | grep -o [0-9]*`
MINOR_VERSION=`grep MINOR_VERSION ../java/sage/Version.java | grep -o [0-9]*`
MICRO_VERSION=`grep MICRO_VERSION ../java/sage/Version.java | grep -o [0-9]*`

ARCH=
if uname -m | grep 64 ; then
	ARCH=amd64
else
	ARCH=i386
fi

cd serverrelease
tar czf ../sagetv-server_"$MAJOR_VERSION"."$MINOR_VERSION"."$MICRO_VERSION"_"$ARCH".tar.gz *

cd ../clientrelease
tar czf ../sagetv-client_"$MAJOR_VERSION"."$MINOR_VERSION"."$MICRO_VERSION"_"$ARCH".tar.gz *

cd ../../
./gradlew packageSageJar
cd build

