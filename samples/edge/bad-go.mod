module edu.course.samples/edge-go-bad

go 1.21

require (
	github.com/gin-gonic/gin v1.9.1
	github.com/google/uuid v1.6.0

// 故意不写右括号，测试 PARSE_ERROR 应携带 require 块起始行号
