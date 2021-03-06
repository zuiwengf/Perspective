
package com.ziyuan.perspective.invokes;

import com.ziyuan.perspective.Constants;
import com.ziyuan.perspective.util.StorageUtil;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.TimeoutException;

/**
 * Trace 一次完整的调用链
 *
 * @author ziyuan
 * @since 2017-02-20
 */
public final class Trace extends AbstractCollectionInvoke {

    /**
     * 所有branch的key - value结构，用于快速找到一个branch
     */
    private Map<String, Branch> allBranches = new ConcurrentHashMap<String, Branch>(16);

    /**
     * 有问题的branch集合
     */
    private Set<Branch> errorBranches = new CopyOnWriteArraySet<Branch>();

    public Trace(String name, String traceId) {
        super(name, traceId);
        String branchId = traceId + "-" + this.getAndIncreaseChildBranchNum();
        Branch b = new Branch(name + "-main", traceId, branchId, this);
        b.setMain(true);
        super.CHILD_BRANCHES.add(b);
        this.allBranches.put(branchId, b);
        StorageUtil.newTrace(this);
    }

    public void newChildBranch(Branch branch) throws Exception {
        super.newChildBranch(branch);
        allBranches.put(branch.getBranchId(), branch);
    }

    @Override
    public String format() {
        StringBuffer sb = new StringBuffer("");
        if (this.isSuccess()) {
            sb.append("Trace is ok !, cost time : ").append(this.getDuration()).append(", branches cost : [");
            for (Branch b : allBranches.values()) {
                sb.append(b.getName()).append(", cost : ").append(b.getDuration()).append("ms").append("\n");
            }
            sb.append("]");
        } else {
            //失败的
            if (this.isTimeOut()) {
                sb.append("Trace timeout : {").append("Trace name -> ").append(this.getName()).append(", ").append("timeout branches is : [ \n");
                List<Branch> timeOutBranches = new ArrayList<Branch>();
                for (Branch b : this.getErrorBranch()) {
                    if (b.isTimeOut()) {
                        timeOutBranches.add(b);
                    }
                }
                Collections.sort(timeOutBranches);
                for (Branch b : timeOutBranches) {
                    sb.append(b.getName()).append(", cost : ").append(b.getDuration()).append("ms").append("\n");
                }
                sb.append("]}");
            } else {
                //出现错误导致中断
                sb.append("Trace error : {").append("Trace name -> ").append(this.getName()).append(", ").append("error branches is : [ \n");
                for (Branch b : errorBranches) {
                    sb.append(b.getName()).append(", the error is : ").append(b.getError()).append("\n");
                }
                sb.append("] \n").append("the break reason is -> ").append(this.getError().getMessage()).append("}");
            }
        }
        return sb.toString();
    }

    public Branch getOneBranch(String branchId) {
        return allBranches.get(branchId);
    }

    /**
     * 为这个trace放入一个branch
     *
     * @param branch branch
     */
    public void putBranch(Branch branch) {
        if (branch != null) {
            allBranches.put(branch.getBranchId(), branch);
        }
    }

    /**
     * 获取错误的branch集合
     *
     * @return 错误的branch集合
     */
    public Set<Branch> getErrorBranch() {
        return errorBranches;
    }

    /**
     * 根据这个branchId 结束一个branch
     *
     * @param branchId branch id
     * @param ender    ender
     */
    public void endOneBranch(String branchId, Ender ender) {
        long duration = ender.getEndTime() - this.getStartTime();
        Branch b = allBranches.get(branchId);
        if (b == null) {
            return;
        }

        b.addEnder(ender);
        //控制这个ender是否是最后一个结束的ender
        boolean flag = this.increaseAndGetEndBranchNum() == this.getChildBranchNum();

        //这里的逻辑用来控制：如果一个trace已经结束了，则不移除这个trace，而是等待后来的ender都返回了，才完成整个trace并移除
        if (this.finished()) {
            if (ender.isSuccess()) {
                if (duration > Constants.TIME_OUT) {
                    errorBranches.add(b);
                }
            } else {
                errorBranches.add(b);
            }
            if (flag) {
                StorageUtil.endOneTrace(this);
            }
            return;
        }

        this.setDuration(duration);
        if (ender.isSuccess()) {
            if (duration < Constants.TIME_OUT) {
                if (flag) {
                    this.setState(InvokeState.OVER);
                }
            } else {
                this.setState(InvokeState.TIMEOUT);
                this.setError(new TimeoutException("time out : cost " + duration + "ms"));
                errorBranches.add(b);
            }
        } else {
            //不成功，直接结束一个trace
            this.setError(ender.getError());
            this.setState(ender.getState());
            this.errorBranches.add(b);
        }
        if (flag) {
            StorageUtil.endOneTrace(this);
        }
    }

    /**
     * 检查自己是否超时
     *
     * @param timestamp 时间点
     * @return 是否超时
     */
    public boolean checkTimeOut(long timestamp) {
        return timestamp - this.getStartTime() > Constants.TIME_OUT;
    }
}
