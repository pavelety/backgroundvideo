var cordova = require('cordova');

var defaultOptions = {camera: 'back', shouldRecordAudio: true, videoBitrate: 40960, audioBitrate: 10240};
var backgroundvideo = {

    start: function (successFunction, errorFunction, filename, options) {
        successFunction = successFunction || function (filePath) {
            console.log('video saved:' + filePath);
        };
        errorFunction = errorFunction || function (error) {
            console.log('video recording error:' + error);
        };
        filename = filename || new Date().getTime() + '';
        options = options || {};
        var args = [filename, options.camera || defaultOptions.camera,
            options.shouldRecordAudio != null ? options.shouldRecordAudio : defaultOptions.shouldRecordAudio,
            options.videoBitrate || defaultOptions.videoBitrate,
            options.audioBitrate || defaultOptions.audioBitrate];
        cordova.exec(successFunction, errorFunction, "backgroundvideo", "start", args);
    },
    stop: function (successFunction, errorFunction) {
        successFunction = successFunction || function (filePath) {
            console.log('video saved:' + filePath);
        };
        errorFunction = errorFunction || function (error) {
            console.log('video recording error:' + error);
        };
        cordova.exec(successFunction, errorFunction, "backgroundvideo", "stop", []);
    },
    //Android: joins the files of one recording (the start callback got a new one each time the camera came back from
    //another app) into the first one on the real timeline; startTimes are the starts of the files in ms
    merge: function (successFunction, errorFunction, filePaths, startTimes) {
        cordova.exec(successFunction, errorFunction, "backgroundvideo", "merge", [filePaths, startTimes]);
    }
};

module.exports = backgroundvideo;
