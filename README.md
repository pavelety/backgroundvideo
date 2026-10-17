# Backgroundvideo

##### A simple Cordova/Phonegap/Ionic plugin to capture MP4 (h.264) video and then display it onscreen via a transparent overlay without affecting app functionality.
#### On Android can record videos with minimized app or even when screen is off. It can be used for spy cameras or for video surveillance.
#### On iOS you need to provide screen insomnia. Video will be correctly finished on app minimized, screen off or incoming call.

## Supported Platforms
- Android
- iOS
- Windows

## How to use
### Install
```
cordova plugin add cordova-backgroundvideo
```
### Usage
#### Start recording
```js
var fileName = new Date().getTime() + '';
var videoOptions = {camera: 'back', shouldRecordAudio: true, videoBitrate: 40960, audioBitrate: 10240};
var successFn = function(filePath) {
    console.log('video started:' + filePath);
};
var errorFn = function(error) {
    console.log('video recording error:' + error);
};
cordova.plugins.backgroundvideo.start(successFn, errorFn, fileName, videoOptions);
```
#### Start recording (minimalistic)
```js
cordova.plugins.backgroundvideo.start(function(filePath) {
    console.log('video started:' + filePath);
});
```
#### VideoOptions : <code>Object</code>

| Name              | Type                  | Default               | Description                                   |
| ----------------- | --------------------- | --------------------- | --------------------------------------------- |
| camera            | <code>string</code>   | <code>back</code>     | 'back' or 'front' to specify camera direction |
| shouldRecordAudio | <code>boolean</code>  | <code>true</code>     | Record video with audio                       |
| videoBitrate      | <code>int</code>      | <code>40960</code>    | Video bitrate in bit per second (bps)         |
| audioBitrate      | <code>int</code>      | <code>10240</code>    | Audio bitrate in bit per second (bps)         |

#### Stop recording
```js
var successFn = function(filePath) {
    console.log('video saved:' + filePath);
};
var errorFn = function(error) {
    console.log('video recording error:' + error);
};
cordova.plugins.backgroundvideo.stop(successFn, errorFn);
```

#### Another app took the camera (Android)

When another app opens the camera (the system camera, a messenger), Android takes it from the recorder. The plugin
closes the current file, waits until the camera is free (it tries every 3 s) and goes on in a new file named by the
time it started (`<ms>.mp4`, in the folder of the first one). The success callback of `start` is called again with
the path of every new file, so keep it until `stop`. A video of one recording can be joined from these files:

```js
var files = [], starts = [];
cordova.plugins.backgroundvideo.start(function (filePath) {
    files.push(filePath);
    starts.push(files.length === 1 ? Date.now() : Number(filePath.split('/').pop().split('.')[0]));
}, errorFn, fileName);
// ...after stop
cordova.plugins.backgroundvideo.merge(function (filePath) {
    console.log('one video of the recording:' + filePath);
}, errorFn, files, starts);
```

`merge` joins the files into the first one on the real timeline: each file starts at its own time, the picture holds
while the camera was away and the sound is silent there. The other files are deleted. When the files can't be joined,
only the first one is kept.
### Other bits
**File**
- Outputs as mp4. You do not need to specify file extension.
- Video files are saved to approot on sdcard or internal (plugin automatically chooses biggest partition).

### Support
Please use the github issue tracker and we will come back to you as soon as we can.

### Contribution
There's lots of Android phones all with their own quirks so we'd love it if you could contribute and help us support all of the devices out there.
