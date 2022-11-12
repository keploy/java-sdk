package io.keploy.ksql;

import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.security.AnyTypePermission;

import java.sql.*;
class App{
    public static void main(String[] args){
        try{
//step1 load the driver class  
//            Class.forName("oracle.jdbc.driver.OracleDriver");

//step2 create  the connection object  
//            Connection con=DriverManager.getConnection(
//                    "jdbc:oracle:thin:@keploy.c4mc5loxg4w2.us-west-2.rds.amazonaws.com:1521:ORCL","admin","UeKGYXIDClDlQY4c1t9P");
            Connection con = DriverManager.getConnection("jdbc:mysql://localhost:3306/notes","mysql","mysql");
            XStream xstream = new XStream();
            xstream.alias("Connection", Connection.class);
            xstream.ignoreUnknownElements();
//            XStreamMarshaller marshaller = new XStreamMarshaller();
//            marshaller.getXStream().ignoreUnknownElements();
            xstream.addPermission(AnyTypePermission.ANY);
            String temp = xstream.toXML(con);
            System.out.println(temp);
            Connection c = (Connection) xstream.fromXML(temp);
//step3 create the statement object  
            Statement stmt=c.createStatement();

//step4 execute query  
            ResultSet rs=stmt.executeQuery("select * from user_table");

            while(rs.next())
                System.out.println(rs.getInt(1)+"  "+rs.getString(2)+"  "+rs.getString(3));

//step5 close the connection object  
            con.close();

        }catch(Exception e){ System.out.println(e);}

    }
}  