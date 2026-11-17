module edu.course.samples/demo-go-single

go 1.21

// 变体样例：手写风格的单行 require（go mod tidy 总是归入块式，故单独保留）
// 覆盖解析器的单行 require 分支 + /v8 主版本后缀原样保留
require github.com/gin-gonic/gin v1.9.1
require github.com/go-redis/redis/v8 v8.11.5 // indirect
