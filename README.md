# RecBiliOld

一个使老版本b站正常播放视频的Xposed模块

> 虽然只在b站蓝版**blue-1.5.3(versionCode:550153)**测试过
> 但是其他版本能不能兼容我就不知道了（笑

请注意，该项目big Powered By AI，会有为了播放视频而不择手段的地方（比如改写x/v2/view响应体，为了解决普通视频播放使用bangumi API请求**get_soures**的问题而hook强制跳过这一阶段）所以屎山有点多

