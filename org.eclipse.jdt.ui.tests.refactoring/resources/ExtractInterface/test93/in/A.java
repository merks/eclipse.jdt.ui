package p;
class A {
	int x;
}
class ST{
	A[] supercs;
}
class T extends ST{
	A[] cs;
	void add(A c){
		super.supercs[0]= c;
		super.supercs[0].x= 0;
	}
}