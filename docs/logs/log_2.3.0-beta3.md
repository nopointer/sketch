优化：
>* 优化了分块显示超大图功能的碎片排序比较器，还对可能在Java7上出现的排序崩溃进行了捕获并可通过ExceptionMonitor监控，另外崩溃时会临时切换成Java6的排序方式重新排序