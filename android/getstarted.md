## Configuring into your project

In order to use envoy, you will need to download the following project from the link https://github.com/greatfire/envoy .
After downloading the project from the above link,  Download the cronet.arr files from ReadMe file or click on the below links. There are two variants of cronet.

**[cronet-debug.aar](https://envoy.greatfire.org/static/cronet-debug.aar)**

**[cronet-release.aar](https://envoy.greatfire.org/static/cronet-release.aar)**

Download both and Copy these files and paste it into directory cronet which is inside folder android.
![image](https://user-images.githubusercontent.com/15171546/89523440-45bd8480-d7fc-11ea-8be9-a40fb5126bb8.png)


Importing this project as a module into your project:

### Step 1

![image](https://user-images.githubusercontent.com/15171546/89523489-5ec63580-d7fc-11ea-8d6e-0d1cbcbff790.png)

### Step 2
Mark check on import module name: cronet and module name: envoy
Mark uncheck on import module name: demo

![image](https://user-images.githubusercontent.com/15171546/89523578-85846c00-d7fc-11ea-933b-5f6e91196b7b.png)

### Step 3
Your project’s minimum sdk should be 21

![image](https://user-images.githubusercontent.com/15171546/89523626-9af99600-d7fc-11ea-96b8-2834ff9df65e.png)


### Step 4
Add following dependencies into your build.gradle (Module:app)

```
implementation project(path: ':envoy')
releaseImplementation project(path: ':cronet', configuration: 'release')
debugImplementation project(path: ':cronet', configuration: 'debug')
```

if you are using Okhttp then add this dependency
```
implementation 'com.squareup.okhttp3:okhttp:4.6.0'
```

if you are using Volley then add this dependency
```
implementation 'com.android.volley:volley:1.1.1'
```

Now sync and build the project. Envoy has successfully integrated into your project.
