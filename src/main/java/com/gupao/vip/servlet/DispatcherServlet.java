package com.gupao.vip.servlet;


import com.gupao.vip.annotation.*;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by chenyingying on 2019/3/26.
 */
public class DispatcherServlet extends HttpServlet {
    //保存 application.properties 配置文件中的内容
    private Properties contextConfig = new Properties();
    //保存扫描的所有的类名
    private List<String> classNames = new ArrayList<String>();
    //传说中的 IOC 容器，我们来揭开它的神秘面纱
    //为了简化程序，暂时不考虑 ConcurrentHashMap
    // 主要还是关注设计思想和原理
    private Map<String,Object> ioc = new HashMap<String,Object>();
   /* //保存 url 和 Method 的对应关系
    private Map<String,Method> handlerMapping = new HashMap<String,Method>();*/
   //保存所有的 Url 和方法的映射关系
   private List<Handler> handlerMapping = new ArrayList<Handler>();

    @Override
    public void init(ServletConfig config) throws ServletException {
        //1、加载配置文件
        doLoadConfig(config.getInitParameter("contextConfigLocation"));
        //2、扫描相关的类
        doScanner(contextConfig.getProperty("scanPackage"));
        //3、初始化扫描到的类，并且将它们放入到 ICO 容器之中
        doInstance();
        //4、完成依赖注入
        doAutowired();
        //5、初始化 HandlerMapping
        initHandlerMapping();
        System.out.println("GP Spring framework is init.");
    }

    private void initHandlerMapping() {
        if (ioc.isEmpty()){return;}

        for (Map.Entry<String,Object> entry:ioc.entrySet()){
            Class<?> clazz = entry.getValue().getClass();

            if (!clazz.isAnnotationPresent(GPController.class)){continue;}

            //保存写在类上面的@GPRequestMapping("/demo")
            String baseUrl = "";
            if (clazz.isAnnotationPresent(GPRequestMapping.class)){
                GPRequestMapping requestMapping = clazz.getAnnotation(GPRequestMapping.class);
                baseUrl = requestMapping.value();
            }

            //默认获取所有的 public 方法
            for (Method method:clazz.getMethods()){
                if (!method.isAnnotationPresent(GPRequestMapping.class)){
                   continue;
                }

                GPRequestMapping requestMapping  = method.getAnnotation(GPRequestMapping.class);
                //优化 // //demo///query
//                handlerMapping.put(url,method);
                String regex = ("/" + baseUrl + requestMapping.value()).replaceAll("/+", "/");
                Pattern pattern = Pattern.compile(regex);
                handlerMapping.add(new Handler(pattern,entry.getValue(),method));
                System.out.println("Mapped :" + baseUrl + "," + method);
            }
        }

    }

    private void doAutowired() {
        if(ioc.isEmpty()){
            return;
        }

        for (Map.Entry<String,Object> entry:ioc.entrySet()){
            //Declared 所有的，特定的 字段，包括 private/protected/default
            // 正常来说，普通的 OOP 编程只能拿到 public 的属性
            Field[] fields = entry.getValue().getClass().getDeclaredFields();
            for(Field field:fields){
                if(!field.isAnnotationPresent(GPAutowired.class)){
                    continue;
                }
                GPAutowired autowired = field.getAnnotation(GPAutowired.class);

                //如果用户没有自定义 beanName，默认就根据类型注入
                // 这个地方省去了对类名首字母小写的情况的判断，这个作为课后作业
                // 小伙伴们自己去完善
                String beanName = autowired.value().trim();
                if("".equals(beanName)){
                    //获得接口的类型，作为 key 待会拿这个 key 到 ioc 容器中去取值
                    beanName = field.getType().getName();
                }

                //如果是 public 以外的修饰符，只要加了@Autowired 注解，都要强制赋值
                // 反射中叫做暴力访问， 强吻
                field.setAccessible(true);

                try {
                    //用反射机制，动态给字段赋值
                    field.set(entry.getValue(),ioc.get(beanName));
                } catch (IllegalAccessException e) {
                    e.printStackTrace();
                }


            }
        }
    }

    //加载配置文件
    private void doLoadConfig(String contextConfigLocation){
        //直接从类路径下找到 Spring 主配置文件所在的路径
        //并且将其读取出来放到 Properties 对象中
        //相对于 scanPackage=com.gupaoedu.demo 从文件中保存到了内存中
        InputStream fis = this.getClass().getClassLoader().getResourceAsStream(contextConfigLocation.replace("classpath:",""));

        try {
            contextConfig.load(fis);

        } catch (IOException e) {
            e.printStackTrace();
        }finally {
            if(null != fis){
                try {
                    fis.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    //扫描出相关的类
    private void doScanner(String scanPackage) {
        //scanPackage = com.gupaoedu.demo ，存储的是包路径
        //转换为文件路径，实际上就是把.替换为/就 OK 了
        //classpath
        URL url = this.getClass().getClassLoader().getResource("/" +
                scanPackage.replaceAll("\\.","/"));
        File classPath = new File(url.getFile());
        for (File file : classPath.listFiles()) {
            if(file.isDirectory()){
                doScanner(scanPackage + "." + file.getName());
            }else {
                if (!file.getName().endsWith(".class")) {
                    continue;
                }
                String className = (scanPackage + "." + file.getName().replace(".class", ""));
                classNames.add(className);
            }
        }
    }

    private void doInstance() {
        //初始化，为 DI 做准备
        if(classNames.isEmpty()){return;}
        try {
            for (String className : classNames) {
                Class<?> clazz = Class.forName(className);
                //什么样的类才需要初始化呢？
                //加了注解的类，才初始化，怎么判断？
                //为了简化代码逻辑，主要体会设计思想，只举例 @Controller 和@Service,
                // @Componment...就一一举例了
                if(clazz.isAnnotationPresent(GPController.class)){
                    Object instance = clazz.newInstance();
                    //Spring 默认类名首字母小写
                    String beanName = toLowerFirstCase(clazz.getSimpleName());
                    ioc.put(beanName,instance);
                }else if(clazz.isAnnotationPresent(GPService.class)){
                    //1、自定义的 beanName
                    GPService service = clazz.getAnnotation(GPService.class);
                    String beanName = service.value();
                    //2、默认类名首字母小写
                    if ("".equals(beanName.trim())){
                        beanName = toLowerFirstCase(clazz.getSimpleName());
                    }

                    Object instance= clazz.newInstance();
                    ioc.put(beanName,instance);
                    //3.根据类型自动赋值,投机取巧的方式
                    for (Class<?> i:clazz.getInterfaces()){
                        if (ioc.containsKey(i.getName())){
                            throw new Exception("The “" + i.getName() + "” is exists!!");
                        }
                        //把接口的类型直接当成key了
                        ioc.put(i.getName(),instance);
                    }
                }
            }
        }catch (Exception e){
            e.printStackTrace();
        }
    }
    //如果类名本身是小写字母，确实会出问题
    //但是我要说明的是：这个方法是我自己用，private 的
    //传值也是自己传，类也都遵循了驼峰命名法
    //默认传入的值，存在首字母小写的情况，也不可能出现非字母的情况
    //为了简化程序逻辑，就不做其他判断了，大家了解就 OK
    //其实用写注释的时间都能够把逻辑写完了
    private String toLowerFirstCase(String simpleName) {
        char [] chars = simpleName.toCharArray();
        //之所以加，是因为大小写字母的 ASCII 码相差 32，
        // 而且大写字母的 ASCII 码要小于小写字母的 ASCII 码
        //在 Java 中，对 char 做算学运算，实际上就是对 ASCII 码做算学运算
        chars[0] += 32;
        return String.valueOf(chars);
    }
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        //6、调用，运行阶段
        try {
            doDispatch(req,resp);
        } catch (Exception e) {
            e.printStackTrace();
            resp.getWriter().write("500 Exection,Detail : " + Arrays.toString(e.getStackTrace()));
        }
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        this.doPost(req,resp);
    }

    private void doDispatch(HttpServletRequest req, HttpServletResponse resp) throws Exception {
//        String url = req.getRequestURI();
//        String contextPath = req.getContextPath();
//        url = url.replaceAll(contextPath,"").replaceAll("/+","/");
//        if(!this.handlerMapping.containsKey(url)){
//            resp.getWriter().write("404 Not Found!!");
//            return;
//        }
//        Method method = this.handlerMapping.get(url);
//        //第一个参数：方法所在的实例
//        //第二个参数：调用时所需要的实参
//        Map<String,String[]> params = req.getParameterMap();
//        //投机取巧的方式
//        /*-----------------------V1版本-----------------------------------------*/
//      /*  String beanName = toLowerFirstCase(method.getDeclaringClass().getSimpleName());
//        method.invoke(ioc.get(beanName),new Object[]{req,resp,params.get("name")[0]});*/
//        /*-----------------------V1版本-----------------------------------------*/
//
//        /*-----------------------V2版本-----------------------------------------*/
//        //提取方法中加了注解的参数
//        //获取方法的形参列表
//        Class<?> [] parameterTypes = method.getParameterTypes();
//        Object [] paramValues = new Object[parameterTypes.length];
//        Annotation[] [] pa = method.getParameterAnnotations();
//        for (int i = 0; i < pa.length ; i ++) {
//            for(Annotation a : pa[i]){
//                if(a instanceof GPRequestParam){
//                    String paramName = ((GPRequestParam) a).value();
//                    if(!"".equals(paramName.trim())){
//                        paramValues[i] = params.get(paramName)[0];
//                    }
//                }
//            }
//        }
//        for (int i = 0; i < parameterTypes.length ; i ++) {
//            Class<?> type = parameterTypes[i];
//            if(type == HttpServletRequest.class){
//                paramValues[i] = req;
//            }else if (type == HttpServletResponse.class){
//                paramValues[i] = resp;
//            }
//        }
//
//        String beanName = toLowerFirstCase(method.getDeclaringClass().getSimpleName());
//        method.invoke(ioc.get(beanName),paramValues);
//    }
    /*-----------------------V2版本-----------------------------------------*/

    Handler handler = getHandler(req);
    if(handler == null){
//        if(!this.handlerMapping.containsKey(url)){
        resp.getWriter().write("404 Not Found!!!");
        return;
    }
    //获得方法的形参列表
    Class<?> [] paramTypes = handler.method.getParameterTypes();
    Object [] paramValues = new Object[paramTypes.length];
    Map<String,String[]> params = req.getParameterMap();
    for (Map.Entry<String, String[]> parm : params.entrySet()) {
        String value = Arrays.toString(parm.getValue()).replaceAll("\\[|\\]","")
                .replaceAll("\\s",",");
        if(!handler.paramIndexMapping.containsKey(parm.getKey())){continue;}
        int index = handler.paramIndexMapping.get(parm.getKey());
        paramValues[index] = convert(paramTypes[index],value);
    }
    if(handler.paramIndexMapping.containsKey(HttpServletRequest.class.getName())) {
        int reqIndex = handler.paramIndexMapping.get(HttpServletRequest.class.getName());
        paramValues[reqIndex] = req;
    }
    if(handler.paramIndexMapping.containsKey(HttpServletResponse.class.getName())) {
        int respIndex = handler.paramIndexMapping.get(HttpServletResponse.class.getName());
        paramValues[respIndex] = resp;
    }
    Object returnValue = handler.method.invoke(handler.controller,paramValues);
    if(returnValue == null || returnValue instanceof Void){ return; }
    resp.getWriter().write(returnValue.toString());
}
    private Handler getHandler(HttpServletRequest req) throws Exception{
        if(handlerMapping.isEmpty()){ return null; }
        String url = req.getRequestURI();
        String contextPath = req.getContextPath();
        url = url.replace(contextPath, "").replaceAll("/+", "/");
        for (Handler handler : handlerMapping) {
            try{
                Matcher matcher = handler.pattern.matcher(url);
                //如果没有匹配上继续下一个匹配
                if(!matcher.matches()){ continue; }
                return handler;
            }catch(Exception e){
                throw e;
            }
        }
        return null;
    }
    private Object convert(Class<?> type,String value){
        if(Integer.class == type){
            return Integer.valueOf(value);
        }
        return value;
    }

    /**
     * Handler 记录 Controller 中的 RequestMapping 和 Method 的对应关系
     * @author Tom
     * 内部类
     */
    private class Handler{
        protected Object controller; //保存方法对应的实例
        protected Method method; //保存映射的方法
        protected Pattern pattern;
        protected Map<String,Integer> paramIndexMapping; //参数顺序
        /**
         * 构造一个 Handler 基本的参数
         * @param controller
         * @param method
         */
        protected Handler(Pattern pattern,Object controller,Method method){
            this.controller = controller;
            this.method = method;
            this.pattern = pattern;
            paramIndexMapping = new HashMap<String,Integer>();
            putParamIndexMapping(method);
        }

        private void putParamIndexMapping(Method method){
        //提取方法中加了注解的参数
            Annotation [] [] pa = method.getParameterAnnotations();
            for (int i = 0; i < pa.length ; i ++) {
                for(Annotation a : pa[i]){
                    if(a instanceof GPRequestParam){
                        String paramName = ((GPRequestParam) a).value();
                        if(!"".equals(paramName.trim())){
                            paramIndexMapping.put(paramName, i);
                        }
                    }
                }
            }
        //提取方法中的 request 和 response 参数
            Class<?> [] paramsTypes = method.getParameterTypes();
            for (int i = 0; i < paramsTypes.length ; i ++) {
                Class<?> type = paramsTypes[i];
                if(type == HttpServletRequest.class ||
                        type == HttpServletResponse.class){
                    paramIndexMapping.put(type.getName(),i);
                }
            }
        }
    }
}
