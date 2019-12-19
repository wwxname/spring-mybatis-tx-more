package com.plusme.lbs.service.controller;

import com.plusme.lbs.service.mapper.StaticEntity;
import com.plusme.lbs.service.mapper.StaticMapper;
import com.plusme.lbs.service.res.StaticRepository;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.BeanNameAware;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Description: lbs-xiaomi
 * Created by martingao on 2018/9/19
 */
@RestController
public class IndexController implements BeanNameAware,BeanFactoryAware ,ApplicationContextAware {


    @Autowired
    StaticMapper staticMapper;
    @Autowired
    StaticRepository staticRepository;


    @GetMapping(value = {"/ok"})
    public String ok() throws InterruptedException {
        return staticRepository.selectOneById(1).toString();
    }

    @GetMapping(value = {"/test"})
    @ResponseBody
    @Transactional(rollbackFor = Exception.class)
    public Object index() {
        StaticEntity one1 = staticMapper.selectOne(1);
        StaticEntity one2 = staticMapper.selectOne(1);
        return "";
    }


    @Override
    public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
        System.err.println(beanFactory);
    }

    @Override
    public void setBeanName(String name) {
        System.err.println(name);
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        System.err.println(applicationContext);
    }
}
