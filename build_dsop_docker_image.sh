# 编译项目
#  --mvn参数需要改为本地mvn可执行文件路径，否则打包会使用项目下的maven
./build/dist --spark-provided --hive-provided --mvn /Users/hupc/01workspace/common-utils/apache-maven-3.6.3/bin/mvn

# 构建镜像
docker buildx build --platform linux/arm64 -f Dockerfile_DSOP -t kyuubi-flink-arm64 .