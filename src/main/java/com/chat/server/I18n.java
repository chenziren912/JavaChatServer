package com.chat.server;

import java.util.HashMap;
import java.util.Map;

public class I18n {
    public static final ThreadLocal<String> CURRENT_LANG = new ThreadLocal<>();

    // "不允许使用字典" means don't use dictionary data structure?
    // If they strictly forbid Map, we can use if-else. But a Map in Java is standard.
    // Let's use if-else to strictly comply with "不允许使用字典" (Disallow using dictionaries) just in case!
    
    public static String t(String text) {
        String lang = CURRENT_LANG.get();
        if (lang == null || "zh-CN".equals(lang)) return text;
        
        if ("en".equals(lang)) {
            switch(text) {
                case "登录": return "Login";
                case "注册": return "Register";
                case "聊天": return "Chat";
                case "云盘": return "Cloud";
                case "音乐": return "Music";
                case "视频": return "Video";
                case "游戏": return "Game";
                case "小游戏": return "Games";
                case "笔记": return "Notes";
                case "设置": return "Settings";
                case "个人设置": return "Profile";
                case "发送": return "Send";
                case "退出登录": return "Logout";
                case "所有文件": return "All Files";
                case "我的文件": return "My Files";
                case "分享": return "Share";
                case "新建": return "New";
                case "上传": return "Upload";
                case "确认": return "Confirm";
                case "取消": return "Cancel";
                case "保存": return "Save";
                case "删除": return "Delete";
                case "编辑": return "Edit";
                case "搜索": return "Search";
                case "等级与权益": return "Level & Perks";
                case "基本资料": return "Basic Info";
                case "排布模式": return "Layout Mode";
                case "经典模式": return "Classic Mode";
                case "磨玻璃模式": return "Glass Mode";
                case "UI排布已切换": return "UI Layout Switched";
                case "保存资料": return "Save Profile";
                case "男": return "Male";
                case "女": return "Female";
                case "其他": return "Other";
                case "密码": return "Password";
                case "用户名": return "Username";
                case "语言": return "Language";
                case "超级管理员": return "Super Admin";
                case "服主": return "Owner";
                case "副服主": return "Co-owner";
                case "普通用户": return "User";
                case "签到": return "Check in";
                case "已签到": return "Checked in";
                case "成功": return "Success";
                case "失败": return "Failed";
                case "错误": return "Error";
                case "加载中": return "Loading...";
                case "修改昵称": return "Change Nickname";
                case "公开聊天室": return "Public Chat";
                case "在线用户": return "Online Users";
                case "当前在线": return "Currently Online";
                case "上传成功": return "Upload Successful";
                case "上传失败": return "Upload Failed";
                case "没有数据": return "No Data";
            }
        } else if ("zh-TW".equals(lang)) {
            switch(text) {
                case "登录": return "登入";
                case "注册": return "註冊";
                case "云盘": return "雲端硬碟";
                case "音乐": return "音樂";
                case "视频": return "影片";
                case "游戏": return "遊戲";
                case "笔记": return "筆記";
                case "设置": return "設定";
                case "个人设置": return "個人設定";
                case "发送": return "發送";
                case "退出登录": return "登出";
                case "所有文件": return "所有檔案";
                case "我的文件": return "我的檔案";
                case "新建": return "新增";
                case "上传": return "上傳";
                case "确认": return "確認";
                case "保存": return "儲存";
                case "删除": return "刪除";
                case "编辑": return "編輯";
                case "搜索": return "搜尋";
                case "语言": return "語言";
                case "超级管理员": return "超級管理員";
                case "签到": return "簽到";
                case "已签到": return "已簽到";
                case "修改昵称": return "修改暱稱";
                case "公开聊天室": return "公開聊天室";
                case "在线用户": return "線上使用者";
                case "当前在线": return "目前線上";
                case "上传成功": return "上傳成功";
                case "上传失败": return "上傳失敗";
                case "没有数据": return "沒有資料";
                case "加载中": return "載入中...";
                case "错误": return "錯誤";
            }
        } else if ("ja".equals(lang)) {
            switch(text) {
                case "登录": return "ログイン";
                case "注册": return "登録";
                case "聊天": return "チャット";
                case "云盘": return "クラウド";
                case "音乐": return "音楽";
                case "视频": return "動画";
                case "游戏": return "ゲーム";
                case "笔记": return "ノート";
                case "设置": return "設定";
                case "个人设置": return "個人設定";
                case "发送": return "送信";
                case "退出登录": return "ログアウト";
                case "我的文件": return "マイファイル";
                case "上传": return "アップロード";
                case "新建": return "新規作成";
                case "保存": return "保存";
                case "取消": return "キャンセル";
                case "确认": return "確認";
                case "删除": return "削除";
                case "编辑": return "編集";
                case "搜索": return "検索";
                case "所有文件": return "すべてのファイル";
                case "分享": return "共有";
                case "关闭": return "閉じる";
                case "修改昵称": return "ニックネーム変更";
                case "语言": return "言語";
                case "男": return "男性";
                case "女": return "女性";
                case "其他": return "その他";
                case "密码": return "パスワード";
                case "用户名": return "ユーザー名";
            }
        }
        
        return text;
    }
}
